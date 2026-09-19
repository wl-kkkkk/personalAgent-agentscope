package com.tyut.agentscope.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyut.agentscope.agent.KnowledgeAgentFactory;
import com.tyut.agentscope.common.StreamEvent;
import com.tyut.agentscope.conversation.entity.Conversation;
import com.tyut.agentscope.conversation.service.ConversationService;
import com.tyut.agentscope.config.AgentMemoryFactory;
import com.tyut.agentscope.hitl.HitlTask;
import com.tyut.agentscope.hitl.HitlTaskRepository;
import com.tyut.agentscope.hitl.PendingToolScan;
import com.tyut.agentscope.hitl.SensitiveTools;
import com.tyut.agentscope.session.AgentSessionStore;
import com.tyut.agentscope.web.AgentResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.SessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

/**
 * 方案一：在调用点显式做 load -> call -> save。
 *
 * 每次请求都新建 agent + memory：记忆是会话级的，单例共享会在并发下串。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final KnowledgeAgentFactory agentFactory;
    private final AgentMemoryFactory memoryFactory;
    private final AgentSessionStore sessionStore;
    private final HitlTaskRepository hitlTaskRepository;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;

    public ChatService(KnowledgeAgentFactory agentFactory,
                       AgentMemoryFactory memoryFactory,
                       AgentSessionStore sessionStore,
                       HitlTaskRepository hitlTaskRepository,
                       ConversationService conversationService,
                       ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.memoryFactory = memoryFactory;
        this.sessionStore = sessionStore;
        this.hitlTaskRepository = hitlTaskRepository;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    public AgentResponse chat(String userId, String nickname, String rootFolder,
                              String sessionId, String query) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String resolvedSessionId = sessionStore.resolveSessionId(sessionId);
        SessionKey key = sessionStore.key(userId, resolvedSessionId);
        conversationService.ensureConversation(userId, resolvedSessionId, query);

        ReActAgent agent = agentFactory.create(memoryFactory.create(), nickname, rootFolder);
        boolean restored = agent.loadIfExists(sessionStore.session(), key);
        log.info("会话 {} 加载历史: {}", key.toIdentifier(), restored ? "命中" : "新建");

        Msg reply;
        try {
            reply = agent.call(Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(query)
                            .build())
                    .block();
        } catch (Exception e) {
            log.error("agent 执行失败, session={}, query={}", key.toIdentifier(), query, e);
            throw new IllegalStateException("对话执行失败: " + e.getMessage(), e);
        } finally {
            // 挂起的 tool call 也在这份 memory 里，一起落库，恢复时才能接着走
            agent.saveTo(sessionStore.session(), key);
            log.info("会话 {} 已保存", key.toIdentifier());
        }

        List<ToolUseBlock> pending = PendingToolScan.findPending(agent.getMemory(), SensitiveTools.NAMES);
        if (!pending.isEmpty()) {
            ToolUseBlock call = pending.get(0);
            HitlTask task = hitlTaskRepository.create(userId, resolvedSessionId,
                    call.getId(), call.getName(), toJson(call.getInput()));
            log.info("[HITL] 任务 {} 已创建，等待审批, 工具={}, session={}",
                    task.taskId(), call.getName(), key.toIdentifier());
            return new AgentResponse(resolvedSessionId, "PENDING_APPROVAL",
                    "已暂停，等待人工审批：" + call.getName(),
                    task.taskId(), call.getName());
        }

        log.info("会话 {} 正常完成", key.toIdentifier());
        return new AgentResponse(resolvedSessionId, "DONE",
                reply == null ? "（没有生成回答）" : reply.getTextContent(), null, null);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("工具入参序列化失败，降级为 toString: {}", value, e);
            return String.valueOf(value);
        }
    }

    // ============================================================
    // 流式对话（SSE）
    // ============================================================

    /**
     * 流式对话：用 {@link Flux#create} 把 AgentScope 的事件流逐段推进 sink，
     * 前端边收边渲染；结束前统一 saveTo，HITL 挂起时额外推一个 approval 事件。
     */
    public Flux<String> streamChat(String userId, String nickname, String rootFolder,
                                   String sessionId, String query) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String resolvedSessionId = sessionStore.resolveSessionId(sessionId);
        SessionKey key = sessionStore.key(userId, resolvedSessionId);

        return Flux.create(sink -> {
            Consumer<StreamEvent> emit = event -> {
                try {
                    sink.next(objectMapper.writeValueAsString(event));
                } catch (Exception e) {
                    log.warn("SSE 事件序列化失败: {}", event, e);
                }
            };

            try {
                Conversation conversation = conversationService.ensureConversation(
                        userId, resolvedSessionId, query);
                emit.accept(StreamEvent.session(resolvedSessionId, conversation.title()));

                ReActAgent agent = agentFactory.create(memoryFactory.create(), nickname, rootFolder);
                boolean restored = agent.loadIfExists(sessionStore.session(), key);
                emit.accept(StreamEvent.progress(restored ? "已加载历史对话" : "新会话，开始处理"));
                emit.accept(StreamEvent.progress("正在思考…"));

                StreamOptions options = StreamOptions.builder()
                        // 三类事件都要：思考过程、工具结果（失败原因看这里）、最终回答
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                        .incremental(true)
                        .includeReasoningChunk(true)
                        .includeReasoningResult(false)
                        .build();

                Msg message = Msg.builder().role(MsgRole.USER).textContent(query).build();
                Disposable[] holder = new Disposable[1];
                holder[0] = agent.stream(List.of(message), options).subscribe(
                        event -> emitEvent(event, emit),
                        error -> {
                            log.error("流式对话失败: session={}", key.toIdentifier(), error);
                            saveQuietly(agent, key);
                            emit.accept(StreamEvent.error(oneLine(error.getMessage())));
                            sink.complete();
                        },
                        () -> {
                            try {
                                saveQuietly(agent, key);
                                emitPendingApproval(agent, userId, resolvedSessionId, emit);
                                emit.accept(StreamEvent.done());
                            } finally {
                                sink.complete();
                            }
                        });
                sink.onCancel(holder[0]::dispose);
                sink.onDispose(holder[0]::dispose);
            } catch (Exception e) {
                log.error("流式对话初始化失败: session={}", key.toIdentifier(), e);
                emit.accept(StreamEvent.error(oneLine(e.getMessage())));
                sink.complete();
            }
        });
    }

    /**
     * 按事件类型分发：
     * <ul>
     *   <li>REASONING：思考增量，只作为"正在思考"的提示；</li>
     *   <li>TOOL_RESULT：工具结果，失败原因也在这里，原样推给前端便于排查；</li>
     *   <li>AGENT_RESULT：最终回答，这才是用户要看的内容。</li>
     * </ul>
     */
    private void emitEvent(Event event, Consumer<StreamEvent> emit) {
        if (event == null || event.getType() == null || event.getMessage() == null) {
            return;
        }
        String text = event.getMessage().getTextContent();
        if (text == null || text.isBlank()) {
            return;
        }
        switch (event.getType()) {
            case REASONING -> emit.accept(StreamEvent.thinking(text));
            case TOOL_RESULT -> {
                log.info("[工具结果] {}", oneLine(text));
                emit.accept(StreamEvent.tool(oneLine(text)));
            }
            case AGENT_RESULT -> emit.accept(StreamEvent.answer(text));
            default -> log.debug("忽略的事件类型: {}", event.getType());
        }
    }

    private void saveQuietly(ReActAgent agent, SessionKey key) {
        try {
            agent.saveTo(sessionStore.session(), key);
        } catch (Exception e) {
            log.error("保存会话失败: {}", key.toIdentifier(), e);
        }
    }

    private void emitPendingApproval(ReActAgent agent, String userId, String sessionId,
                                     Consumer<StreamEvent> emit) {
        List<ToolUseBlock> pending = PendingToolScan.findPending(agent.getMemory(), SensitiveTools.NAMES);
        if (pending.isEmpty()) {
            return;
        }
        ToolUseBlock call = pending.get(0);
        HitlTask task = hitlTaskRepository.create(userId, sessionId,
                call.getId(), call.getName(), toJson(call.getInput()));
        log.info("[HITL] 任务 {} 已创建（流式），工具={}, session={}:{}",
                task.taskId(), call.getName(), userId, sessionId);
        emit.accept(StreamEvent.approval(task.taskId(), call.getName()));
    }

    /** SSE 的 message 字段不能带换行，压成一行 */
    private String oneLine(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}

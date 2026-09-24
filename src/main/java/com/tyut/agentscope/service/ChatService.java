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
import com.tyut.agentscope.keyword.KeywordLibrary;
import com.tyut.agentscope.keyword.Keywords;
import com.tyut.agentscope.session.AgentSessionStore;
import com.tyut.agentscope.web.AgentResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.SessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final KeywordLibrary keywordLibrary;
    private final ObjectMapper objectMapper;

    public ChatService(KnowledgeAgentFactory agentFactory,
                       AgentMemoryFactory memoryFactory,
                       AgentSessionStore sessionStore,
                       HitlTaskRepository hitlTaskRepository,
                       ConversationService conversationService,
                       KeywordLibrary keywordLibrary,
                       ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.memoryFactory = memoryFactory;
        this.sessionStore = sessionStore;
        this.hitlTaskRepository = hitlTaskRepository;
        this.conversationService = conversationService;
        this.keywordLibrary = keywordLibrary;
        this.objectMapper = objectMapper;
    }

    public AgentResponse chat(String userId, String nickname, String rootFolder,
                              String sessionId, String query, String forcedRoute) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        String resolvedSessionId = sessionStore.resolveSessionId(sessionId);
        SessionKey key = sessionStore.key(userId, resolvedSessionId);
        conversationService.ensureConversation(userId, resolvedSessionId, query);

        ReActAgent agent = agentFactory.create(memoryFactory.create(), userId, nickname,
                rootFolder, forcedRoute);
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
                                   String sessionId, String query, String forcedRoute) {
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

                ReActAgent agent = agentFactory.create(memoryFactory.create(), userId, nickname,
                        rootFolder, forcedRoute);
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
    void emitEvent(Event event, Consumer<StreamEvent> emit) {
        if (event == null || event.getType() == null || event.getMessage() == null) {
            return;
        }
        String text = textOf(event.getMessage());
        switch (event.getType()) {
            case REASONING -> {
                if (text.isBlank()) {
                    return;
                }
                emit.accept(StreamEvent.thinking(text));
            }
            case TOOL_RESULT -> {
                // 工具通过 ToolEmitter 推的中间片段 isLast=false，只当进度提示；
                // 工具真正返回的那条 isLast=true，才是"工具结果"，留在气泡里方便排查。
                String label = toolLabel(event.getMessage(), text);
                if (label.isBlank()) {
                    log.debug("工具事件没有可展示的文本，跳过");
                    return;
                }
                if (event.isLast()) {
                    log.info("[工具结果] {}", label);
                    emit.accept(StreamEvent.tool(label));
                } else {
                    log.info("[工具进度] {}", label);
                    emit.accept(StreamEvent.progress(label));
                }
            }
            case AGENT_RESULT -> {
                if (text.isBlank()) {
                    return;
                }
                emit.accept(StreamEvent.answer(text));
            }
            default -> log.debug("忽略的事件类型: {}", event.getType());
        }
    }

    /**
     * 从消息里取纯文本。
     *
     * <p><b>为什么不能直接用 {@code Msg.getTextContent()}</b>：它只收集
     * {@link TextBlock}，而框架给工具事件的 TOOL 消息里装的是 {@link ToolResultBlock}
     * （它是 ContentBlock 的子类，不是 TextBlock）。所以直接调用会拿到空串，
     * 工具事件会被整段丢掉——表现为"前端永远看不到工具调用内容"。
     * 这里对 ToolResultBlock 再展开一层 output。
     */
    private String textOf(Msg message) {
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : message.getContent()) {
            if (block instanceof TextBlock text) {
                parts.add(text.getText());
            } else if (block instanceof ToolResultBlock result) {
                for (ContentBlock output : result.getOutput()) {
                    if (output instanceof TextBlock text) {
                        parts.add(text.getText());
                    }
                }
            }
        }
        return String.join("\n", parts).trim();
    }

    /** 工具事件带上工具名，前端那行才知道是谁在跑（框架会把 id/name 填进 ToolResultBlock） */
    private String toolLabel(Msg message, String text) {
        // 先判空再交给 oneLine：oneLine 对空串有"未知错误"的兜底（那是给 error 事件用的），
        // 用在这里会把"没有内容的工具事件"变成一行莫名其妙的东西。
        if (text == null || text.isBlank()) {
            return "";
        }
        String body = oneLine(text);
        String name = null;
        for (ContentBlock block : message.getContent()) {
            if (block instanceof ToolResultBlock result
                    && result.getName() != null && !result.getName().isBlank()) {
                name = result.getName();
                break;
            }
        }
        return name == null ? body : name + " · " + body;
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

        // 候选关键词从工具入参里取，并与该用户词表比一遍：前端据此渲染可勾选、可标注"新增"的标签。
        // 对比放在服务端做，不指望模型自己算集合差。
        List<String> candidates = Keywords.fromInput(asMap(call.getInput()));
        List<String> existing = candidates.isEmpty()
                ? List.of()
                : keywordLibrary.list(userId);
        emit.accept(StreamEvent.approval(task.taskId(), call.getName(), candidates, existing));
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        // 有的版本里 getInput() 给的是 JSON 字符串，这里一并兜住
        if (value instanceof String text && !text.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(text, Map.class);
                return parsed;
            } catch (Exception e) {
                log.debug("工具入参不是 JSON 对象，按无入参处理: {}", text);
            }
        }
        return Map.of();
    }

    /** SSE 的 message 字段不能带换行，压成一行 */
    private String oneLine(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.replaceAll("\\s+", " ").trim();
    }
}

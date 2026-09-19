package com.tyut.agentscope.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tyut.agentscope.agent.KnowledgeAgentFactory;
import com.tyut.agentscope.config.AgentMemoryFactory;
import com.tyut.agentscope.hitl.HitlTask;
import com.tyut.agentscope.hitl.HitlTaskRepository;
import com.tyut.agentscope.hitl.PendingToolScan;
import com.tyut.agentscope.session.AgentSessionStore;
import com.tyut.agentscope.tool.UploadTools;
import com.tyut.agentscope.user.repository.UserInfoRepository;
import com.tyut.agentscope.web.AgentResponse;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 审批后的恢复：构造一个真实的 ToolResultBlock 回填，id 必须与挂起时的 ToolUseBlock 一致，
 * 否则框架会把它当成没有结果的孤儿调用（PendingToolRecoveryHook 会填 error 结果顶掉）。
 */
@Service
public class HitlService {

    private static final Logger log = LoggerFactory.getLogger(HitlService.class);

    private final HitlTaskRepository hitlTaskRepository;
    private final AgentSessionStore sessionStore;
    private final KnowledgeAgentFactory agentFactory;
    private final AgentMemoryFactory memoryFactory;
    private final UploadTools uploadTools;
    private final UserInfoRepository userInfoRepository;
    private final ObjectMapper objectMapper;

    public HitlService(HitlTaskRepository hitlTaskRepository,
                       AgentSessionStore sessionStore,
                       KnowledgeAgentFactory agentFactory,
                       AgentMemoryFactory memoryFactory,
                       UploadTools uploadTools,
                       UserInfoRepository userInfoRepository,
                       ObjectMapper objectMapper) {
        this.hitlTaskRepository = hitlTaskRepository;
        this.sessionStore = sessionStore;
        this.agentFactory = agentFactory;
        this.memoryFactory = memoryFactory;
        this.uploadTools = uploadTools;
        this.userInfoRepository = userInfoRepository;
        this.objectMapper = objectMapper;
    }

    public AgentResponse approve(String taskId, boolean approved, String note, String currentUserId) {
        HitlTask task = hitlTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalArgumentException("审批任务不存在: " + taskId));
        if (currentUserId != null && !currentUserId.equals(task.userId())) {
            log.warn("拒绝越权审批: 当前用户={}, 任务归属={}", currentUserId, task.userId());
            throw new IllegalArgumentException("该审批任务不属于当前用户");
        }
        if (!HitlTask.PENDING.equals(task.status())) {
            throw new IllegalStateException("该任务已处理: " + task.status());
        }
        hitlTaskRepository.updateDecision(taskId,
                approved ? HitlTask.APPROVED : HitlTask.REJECTED, note);
        log.info("[HITL] 任务 {} 审批结果={}, 工具={}, session={}:{}",
                taskId, approved ? "通过" : "拒绝", task.toolName(), task.userId(), task.sessionId());

        SessionKey key = sessionStore.key(task.userId(), task.sessionId());
        var userInfo = userInfoRepository.findById(task.userId());
        String nickname = userInfo.map(u -> u.nickname()).orElse(null);
        String rootFolder = userInfo.map(u -> u.rootFolder()).orElse(null);
        ReActAgent agent = agentFactory.create(memoryFactory.create(), nickname, rootFolder);
        boolean restored = agent.loadIfExists(sessionStore.session(), key);
        if (!restored) {
            log.warn("[HITL] 任务 {} 恢复时没有找到会话记忆, key={}，将按全新会话继续", taskId, key.toIdentifier());
        }

        String resultText = approved ? executeApprovedTool(task) : "人工已拒绝，本次上传未执行。";

        ToolUseBlock toolUse = new ToolUseBlock(task.toolUseId(), task.toolName(), parseInput(task.toolInput()));
        if (!PendingToolScan.containsToolUseId(agent.getMemory(), task.toolUseId())) {
            // 挂起的 assistant 消息没有随记忆回来（取决于框架是否落盘），这里按任务记录重建，
            // 否则回填的 ToolResultBlock 没有可与配对的 tool call，会被判为孤儿调用。
            log.warn("[HITL] 记忆中没有找到挂起的 tool call, 按任务记录重建: taskId={}, toolUseId={}",
                    taskId, task.toolUseId());
            agent.getMemory().addMessage(Msg.builder()
                    .role(MsgRole.ASSISTANT)
                    .content(toolUse)
                    .build());
        }

        ToolResultBlock toolResult = ToolResultBlock.builder()
                .id(toolUse.getId())
                .name(toolUse.getName())
                .output(TextBlock.builder().text(resultText).build())
                .build();
        Msg toolResultMessage = ToolResultMessageBuilder.buildToolResultMsg(
                toolResult, toolUse, toolUse.getName());

        try {
            Msg reply = agent.call(toolResultMessage).block();
            log.info("[HITL] 任务 {} 已恢复并执行完成", taskId);
            return new AgentResponse(task.sessionId(), "DONE",
                    reply == null ? "（没有生成回答）" : reply.getTextContent(),
                    taskId, task.toolName());
        } finally {
            agent.saveTo(sessionStore.session(), key);
        }
    }

    /** 审批通过才真正执行工具；当前敏感工具只有 upload_document。 */
    private String executeApprovedTool(HitlTask task) {
        Map<String, Object> input = parseInput(task.toolInput());
        // 参数名不固定：本地包装工具是 title/markdownContent，直接调 MCP 是 documentName/markdownContent，
        // 而模型有时只传一个 content（整个正文）。这里逐个别名兜底，别因为少个字段就放弃上传。
        String content = firstNonBlank(input.get("markdownContent"), input.get("content"),
                input.get("markdown"), input.get("text"));
        if (content.isBlank()) {
            log.error("[HITL] 审批通过但拿不到文档内容, taskId={}, input={}", task.taskId(), task.toolInput());
            return "上传失败：工具入参里没有文档内容。原始入参：" + task.toolInput();
        }
        String title = firstNonBlank(input.get("title"), input.get("documentName"), input.get("name"));
        if (title.isBlank()) {
            title = deriveTitle(content);
            log.info("[HITL] 入参里没有标题，用正文推导: {}", title);
        }
        return uploadTools.uploadDocument(title, content);
    }

    /** 从正文推导标题：优先第一个 Markdown 标题，其次第一行，最后给个默认名 */
    private String deriveTitle(String content) {
        for (String rawLine : content.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String withoutHash = line.replaceFirst("^#+\\s*", "").trim();
            if (!withoutHash.isEmpty()) {
                return withoutHash.length() > 40 ? withoutHash.substring(0, 40) : withoutHash;
            }
        }
        return "知识文档-" + System.currentTimeMillis();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("工具入参解析失败: " + json, e);
        }
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }
}

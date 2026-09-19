package com.tyut.agentscope.conversation.service.impl;

import com.tyut.agentscope.common.ModelCaller;
import com.tyut.agentscope.common.PromptLoader;
import com.tyut.agentscope.conversation.entity.Conversation;
import com.tyut.agentscope.conversation.repository.ConversationRepository;
import com.tyut.agentscope.conversation.service.ConversationService;
import com.tyut.agentscope.hitl.HitlTaskRepository;
import com.tyut.agentscope.session.AgentSessionStore;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * <h2>会话元信息</h2>
 *
 * <p>标题生成照 personalrag 的做法：先用第一句话截一段当临时标题马上落库（界面立刻有东西显示），
 * 再用虚拟线程异步调轻量模型生成正式标题回写，失败就保留临时标题，不影响对话。
 */
@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationServiceImpl.class);
    private static final int TEMP_TITLE_LENGTH = 20;

    private final ConversationRepository conversationRepository;
    private final AgentSessionStore sessionStore;
    private final HitlTaskRepository hitlTaskRepository;
    private final Model titleModel;
    private final ModelCaller modelCaller;
    private final PromptLoader promptLoader;

    public ConversationServiceImpl(ConversationRepository conversationRepository,
                                   AgentSessionStore sessionStore,
                                   HitlTaskRepository hitlTaskRepository,
                                   @Qualifier("titleModel") Model titleModel,
                                   ModelCaller modelCaller,
                                   PromptLoader promptLoader) {
        this.conversationRepository = conversationRepository;
        this.sessionStore = sessionStore;
        this.hitlTaskRepository = hitlTaskRepository;
        this.titleModel = titleModel;
        this.modelCaller = modelCaller;
        this.promptLoader = promptLoader;
    }

    @Override
    public Conversation ensureConversation(String userId, String sessionId, String firstMessage) {
        var existing = conversationRepository.findById(sessionId);
        if (existing.isPresent()) {
            conversationRepository.touch(sessionId);
            return existing.get();
        }

        String tempTitle = firstMessage == null || firstMessage.isBlank()
                ? "新对话"
                : firstMessage.substring(0, Math.min(firstMessage.length(), TEMP_TITLE_LENGTH));
        conversationRepository.create(sessionId, userId, tempTitle);
        log.info("创建会话: conversationId={}, userId={}, 临时标题={}", sessionId, userId, tempTitle);

        // 异步生成正式标题：不阻塞对话，失败也不影响主流程
        if (firstMessage != null && !firstMessage.isBlank()) {
            Thread.ofVirtual().name("title-" + sessionId).start(() -> {
                try {
                    String title = generateTitle(firstMessage);
                    if (title != null && !title.isBlank()) {
                        conversationRepository.updateTitle(sessionId, title);
                    }
                } catch (Exception e) {
                    log.warn("生成会话标题失败，保留临时标题: conversationId={}", sessionId, e);
                }
            });
        }
        return conversationRepository.findById(sessionId).orElseThrow();
    }

    @Override
    public List<Conversation> listByUser(String userId) {
        return conversationRepository.findByUser(userId);
    }

    @Override
    public void delete(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: " + conversationId));
        if (!userId.equals(conversation.userId())) {
            log.warn("拒绝越权删除会话: 当前用户={}, 会话归属={}", userId, conversation.userId());
            throw new IllegalArgumentException("该会话不属于当前用户");
        }

        conversationRepository.delete(conversationId);

        // 会话记忆（AgentScope 存在 agentscope_sessions），key 规则与 ChatService 保持一致
        try {
            sessionStore.session().delete(sessionStore.key(userId, conversationId));
        } catch (Exception e) {
            // 元信息已删，记忆删不掉只记日志，不影响用户看到的结果
            log.error("删除会话记忆失败: conversationId={}", conversationId, e);
        }

        int removedTasks = hitlTaskRepository.deleteBySession(userId, conversationId);
        log.info("会话已删除: conversationId={}, userId={}, 一并清理审批任务 {} 条",
                conversationId, userId, removedTasks);
    }

    private String generateTitle(String content) {
        String prompt = promptLoader.load("prompts/conversation-title.st", Map.of("content", content));
        String title = modelCaller.call(titleModel, prompt);
        if (title == null) {
            return null;
        }
        // 去掉可能的引号、换行，并限制长度
        String cleaned = title.replaceAll("[\"'“”\\r\\n]", "").trim();
        return cleaned.length() > TEMP_TITLE_LENGTH ? cleaned.substring(0, TEMP_TITLE_LENGTH) : cleaned;
    }
}

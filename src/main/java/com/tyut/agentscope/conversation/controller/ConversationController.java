package com.tyut.agentscope.conversation.controller;

import com.tyut.agentscope.common.ApiResponse;
import com.tyut.agentscope.conversation.entity.Conversation;
import com.tyut.agentscope.conversation.service.ConversationService;
import com.tyut.agentscope.user.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话列表接口（当前用户）。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final Logger log = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationService conversationService;
    private final AuthService authService;

    public ConversationController(ConversationService conversationService, AuthService authService) {
        this.conversationService = conversationService;
        this.authService = authService;
    }

    @GetMapping
    public ApiResponse<List<Conversation>> list() {
        try {
            return ApiResponse.ok(conversationService.listByUser(authService.currentUserId()));
        } catch (Exception e) {
            log.warn("获取会话列表失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        }
    }

    /** 删除会话：只能删自己的，会话记忆与挂起的审批任务一并清理 */
    @DeleteMapping("/{conversationId}")
    public ApiResponse<Void> delete(@PathVariable String conversationId) {
        try {
            conversationService.delete(authService.currentUserId(), conversationId);
            return ApiResponse.<Void>ok("删除成功", null);
        } catch (IllegalArgumentException e) {
            log.warn("删除会话失败: {}", e.getMessage());
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            log.error("删除会话异常: conversationId={}", conversationId, e);
            return ApiResponse.fail("删除失败：" + e.getMessage());
        }
    }
}

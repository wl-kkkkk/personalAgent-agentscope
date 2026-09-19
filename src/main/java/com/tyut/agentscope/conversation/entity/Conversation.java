package com.tyut.agentscope.conversation.entity;

import java.time.LocalDateTime;

/**
 * 会话元信息。记忆本体在 agentscope_sessions，这张表只给界面用。
 */
public record Conversation(
        String conversationId,
        String userId,
        String title,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}

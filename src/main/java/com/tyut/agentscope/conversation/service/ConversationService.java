package com.tyut.agentscope.conversation.service;

import com.tyut.agentscope.conversation.entity.Conversation;

import java.util.List;

public interface ConversationService {

    /** 会话不存在就建（顺带触发异步标题生成），存在就刷新时间；返回当前会话 */
    Conversation ensureConversation(String userId, String sessionId, String firstMessage);

    List<Conversation> listByUser(String userId);

    /** 删除会话：连同会话记忆与挂起的审批任务一起清理，只能删自己的 */
    void delete(String userId, String conversationId);
}

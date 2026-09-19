package com.tyut.agentscope.web;

/**
 * status = DONE 时看 answer；status = PENDING_APPROVAL 时看 taskId 与 toolName。
 */
public record AgentResponse(String sessionId,
                            String status,
                            String answer,
                            String taskId,
                            String toolName) {
}

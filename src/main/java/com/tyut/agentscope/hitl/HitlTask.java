package com.tyut.agentscope.hitl;

/**
 * 一条挂起待审批的工具调用。
 */
public record HitlTask(
        String taskId,
        String userId,
        String sessionId,
        String toolUseId,
        String toolName,
        String toolInput,
        String status,
        String decisionNote) {

    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
}

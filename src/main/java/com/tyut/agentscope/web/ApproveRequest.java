package com.tyut.agentscope.web;

import java.util.List;

/**
 * 审批请求体。
 *
 * <p>{@code keywords} 是用户在审批卡片上确认的关键词：可以勾掉不要的、也可以自己补词。
 * 传 null 表示没带（按工具入参里的候选处理），传空数组表示"一个都不要"。
 */
public record ApproveRequest(String taskId, boolean approved, String note, List<String> keywords) {
}

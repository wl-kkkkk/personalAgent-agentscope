package com.tyut.agentscope.hitl;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 memory 里找出"模型请求了、但没有结果"的敏感工具调用。
 * 判定逻辑与框架的 PendingToolRecoveryHook 一致：ASSISTANT 消息里的 ToolUseBlock
 * 减去 TOOL 消息里已回填的 ToolResultBlock，剩下的就是挂起的。
 */
public final class PendingToolScan {

    private PendingToolScan() {
    }

    public static List<ToolUseBlock> findPending(Memory memory, Set<String> sensitiveTools) {
        if (memory == null) {
            return List.of();
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        List<ToolUseBlock> toolUses = new ArrayList<>();
        Set<String> resolvedIds = new HashSet<>();

        for (Msg message : messages) {
            if (message.getRole() == MsgRole.ASSISTANT) {
                toolUses.addAll(message.getContentBlocks(ToolUseBlock.class));
            } else if (message.getRole() == MsgRole.TOOL) {
                for (ToolResultBlock result : message.getContentBlocks(ToolResultBlock.class)) {
                    resolvedIds.add(result.getId());
                }
            }
        }

        return toolUses.stream()
                .filter(use -> !resolvedIds.contains(use.getId()))
                .filter(use -> sensitiveTools.contains(use.getName()))
                .toList();
    }

    /**
     * 记忆里是否存在指定 id 的 tool call。
     * <p>恢复时用它判断"挂起的调用是否随记忆一起回来了"：<br>
     * 如果没回来，就必须根据 HITL 任务记录把这条 assistant 消息重建出来，
     * 否则回填的 ToolResultBlock 会变成没有 tool call 与之配对的孤儿消息。
     *
     * @param memory    会话记忆，可为 null
     * @param toolUseId 挂起时的 ToolUseBlock id
     * @return 存在返回 true
     */
    public static boolean containsToolUseId(Memory memory, String toolUseId) {
        if (memory == null || toolUseId == null || toolUseId.isBlank()) {
            return false;
        }
        List<Msg> messages = memory.getMessages();
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Msg message : messages) {
            if (message.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            for (ToolUseBlock toolUse : message.getContentBlocks(ToolUseBlock.class)) {
                if (toolUseId.equals(toolUse.getId())) {
                    return true;
                }
            }
        }
        return false;
    }
}

package com.tyut.agentscope;

import com.tyut.agentscope.hitl.PendingToolScan;
import com.tyut.agentscope.hitl.SensitiveTools;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 的核心不变量：ToolResultBlock 的 id 必须与挂起的 ToolUseBlock 精确匹配，
 * 匹配上才算"已解决"，否则会被当成挂起调用。
 */
class PendingToolScanTest {

    @Test
    void detectsSensitiveCallWithoutResult() {
        InMemoryMemory memory = new InMemoryMemory();
        memory.addMessage(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("call-1", "upload_document", Map.of("title", "t")))
                .build());

        assertThat(PendingToolScan.findPending(memory, SensitiveTools.NAMES))
                .hasSize(1);
    }

    @Test
    void ignoresCallOnceResultBackfilled() {
        InMemoryMemory memory = new InMemoryMemory();
        memory.addMessage(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("call-1", "upload_document", Map.of()))
                .build());
        memory.addMessage(Msg.builder()
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.builder()
                        .id("call-1")
                        .name("upload_document")
                        .output(TextBlock.builder().text("已上传").build())
                        .build())
                .build());

        assertThat(PendingToolScan.findPending(memory, SensitiveTools.NAMES)).isEmpty();
    }

    @Test
    void ignoresNonSensitiveTools() {
        InMemoryMemory memory = new InMemoryMemory();
        memory.addMessage(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("call-2", "web_search", Map.of("query", "x")))
                .build());

        assertThat(PendingToolScan.findPending(memory, SensitiveTools.NAMES)).isEmpty();
    }

    @Test
    void detectsWhetherPendingCallSurvivesInMemory() {
        InMemoryMemory empty = new InMemoryMemory();
        assertThat(PendingToolScan.containsToolUseId(empty, "call-1")).isFalse();

        InMemoryMemory memory = new InMemoryMemory();
        memory.addMessage(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(new ToolUseBlock("call-1", "upload_document", Map.of()))
                .build());
        assertThat(PendingToolScan.containsToolUseId(memory, "call-1")).isTrue();
        assertThat(PendingToolScan.containsToolUseId(memory, "call-other")).isFalse();
    }
}

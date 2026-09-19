package com.tyut.agentscope.config;

import io.agentscope.core.memory.Memory;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.model.Model;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 第一层：短期记忆。统一构建带自动压缩/卸载能力的 AutoContextMemory。
 *
 * 每次调用返回一个**新实例**——记忆是会话级的，不能共享。
 */
@Component
public class AgentMemoryFactory {

    private final Model chatModel;

    public AgentMemoryFactory(@Qualifier("chatModel") Model chatModel) {
        this.chatModel = chatModel;
    }

    public Memory create() {
        AutoContextConfig config = AutoContextConfig.builder()
                // 单条消息超过 4KB 即卸载，卸载后保留 300 字符预览
                .largePayloadThreshold(4 * 1024L)
                .offloadSinglePreview(300)
                // 上下文窗口上限 128K，达到 75%（96K）触发压缩
                .maxToken(128 * 1024L)
                .tokenRatio(0.75)
                // 消息条数超过 60 条触发压缩，保留最近 20 条
                .msgThreshold(60)
                .lastKeep(20)
                .build();
        return new AutoContextMemory(config, chatModel);
    }
}

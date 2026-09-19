package com.tyut.agentscope.hook;

import com.tyut.agentscope.hitl.SensitiveTools;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 人工审批（HITL）：挂在 PostReasoningEvent 上。
 *
 * 为什么是 PostReasoning 而不是 PreActing：模型刚产出 tool calls、还没执行，这是
 * 唯一能"拦下来但不执行"的时机。PreActing 时工具已经准备执行了，拦了也会被当成失败。
 *
 * 这里只负责"停下"；把挂起的调用落库、以及审批后的恢复，分别在 ChatService 和 HitlService 里做。
 */
@Component
public class UploadApprovalHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(UploadApprovalHook.class);

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostReasoningEvent postReasoning) {
            List<ToolUseBlock> toolCalls = postReasoning.getReasoningMessage() == null
                    ? List.of()
                    : postReasoning.getReasoningMessage().getContentBlocks(ToolUseBlock.class);

            boolean hasSensitive = toolCalls.stream()
                    .anyMatch(call -> SensitiveTools.NAMES.contains(call.getName()));

            if (hasSensitive) {
                log.info("[HITL] 检测到敏感工具调用，暂停等待人工审批: {}", 
                        toolCalls.stream().map(ToolUseBlock::getName).toList());
                postReasoning.stopAgent();
            }
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 100;
    }
}

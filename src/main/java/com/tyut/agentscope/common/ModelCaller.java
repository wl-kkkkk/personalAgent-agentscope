package com.tyut.agentscope.common;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <h2>模型调用器</h2>
 * <p>
 * 把"拼消息 → 调模型 → 拼文本"这段重复逻辑收在一处，统一处理三种边界：
 * 提示词为空、返回为空、调用超时或抛异常。
 */
@Component
public class ModelCaller {

    private static final Logger log = LoggerFactory.getLogger(ModelCaller.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * 用一次纯文本对话调用模型。
     *
     * @param model  目标模型
     * @param prompt 提示词，不能为空
     * @return 模型输出的文本；无内容时返回空串（不返回 null）
     */
    public String call(Model model, String prompt) {
        if (model == null) {
            throw new IllegalArgumentException("模型不能为 null");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        String modelName = model.getModelName();
        try {
            Msg message = Msg.builder().role(MsgRole.USER).textContent(prompt).build();
            List<ChatResponse> responses = model.stream(List.of(message), List.of(), null)
                    .collectList()
                    .block(TIMEOUT);
            if (responses == null || responses.isEmpty()) {
                log.warn("模型未返回任何内容, model={}", modelName);
                return "";
            }
            return responses.stream()
                    .flatMap(response -> response.getContent().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(block -> ((TextBlock) block).getText())
                    .collect(Collectors.joining())
                    .trim();
        } catch (Exception e) {
            String reason = e.getMessage();
            // 提取底层 DashScope API 的 HTTP 状态码与错误描述
            String causeMsg = (e.getCause() != null) ? e.getCause().getMessage() : "";
            log.error("调用模型失败, model={}, error={}, cause={}", modelName, reason, causeMsg, e);
            throw new IllegalStateException("调用模型失败: model=" + modelName
                    + ", error=" + reason
                    + (causeMsg.isBlank() ? "" : ", cause=" + causeMsg), e);
        }
    }
}
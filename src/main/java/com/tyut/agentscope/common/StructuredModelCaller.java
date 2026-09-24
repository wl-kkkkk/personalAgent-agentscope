package com.tyut.agentscope.common;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * <h2>结构化输出调用器</h2>
 *
 * <p>把"调模型 → 拿 JSON → 映射成 record"这段收在一处，对标 personalrag 里 Spring AI 的
 * {@code chatClient.prompt().entity(XxxResult.class)}。AgentScope 1.0.12 的 {@code Model}
 * 接口没有 responseFormat 参数（它自己的结构化输出是走临时 tool + tool_choice 实现的，
 * 那是给"整个 agent 的最终输出"用的，不适合 Hook 里的单次分类调用），所以这里用
 * "提示词约束 + 容错解析 + 类型映射"来实现同样的效果。
 *
 * <p>{@code app.llm.json-mode=true} 会额外带上 DashScope 的
 * {@code response_format={"type":"json_object"}}，约束更硬；但 DashScope 部分端点
 * （比如本项目里走多模态端点的 qwen3.8 系列）不一定支持这个参数，所以默认关闭。
 * 打开之前先按 README 的探针方式确认目标模型能吃下这个参数。
 */
@Component
public class StructuredModelCaller {

    private static final Logger log = LoggerFactory.getLogger(StructuredModelCaller.class);

    private final ModelCaller modelCaller;
    private final JsonSupport jsonSupport;
    private final boolean jsonModeEnabled;

    public StructuredModelCaller(ModelCaller modelCaller,
                                 JsonSupport jsonSupport,
                                 @Value("${app.llm.json-mode:false}") boolean jsonModeEnabled) {
        this.modelCaller = modelCaller;
        this.jsonSupport = jsonSupport;
        this.jsonModeEnabled = jsonModeEnabled;
    }

    /**
     * 用一次纯文本调用拿结构化结果。
     *
     * @return 解析成功返回对象；模型返回为空、JSON 残缺、字段对不上都返回 empty
     */
    public <T> Optional<T> call(Model model, String prompt, Class<T> type) {
        if (model == null || type == null) {
            throw new IllegalArgumentException("模型与目标类型都不能为 null");
        }
        String raw = modelCaller.call(model, prompt, buildOptions());
        Optional<T> parsed = jsonSupport.parse(raw, type);
        if (parsed.isEmpty()) {
            log.warn("结构化输出未取到 {}，将走调用方的降级分支", type.getSimpleName());
        }
        return parsed;
    }

    private GenerateOptions buildOptions() {
        if (!jsonModeEnabled) {
            return null;
        }
        return GenerateOptions.builder()
                .additionalBodyParam("response_format", Map.of("type", "json_object"))
                .build();
    }
}

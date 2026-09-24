package com.tyut.agentscope.intent;

import com.tyut.agentscope.common.PromptLoader;
import com.tyut.agentscope.common.StructuredModelCaller;
import com.tyut.agentscope.keyword.Keywords;
import io.agentscope.core.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * <h2>意图识别（结构化输出）</h2>
 *
 * <p>照着 personalrag 的分层来：那边是"意图识别决定要不要检索 + 查询路由决定怎么检索"，
 * 在 Agent 侧这两件事合成一次结构化调用就够了，因为 Agent 只有两条路可选：
 * 个人知识库 / 联网检索。
 *
 * <p><b>词表怎么用</b>：提示词里带上该用户的完整词表，让模型做"匹配"而不是"生成"——
 * 命中的关键词必须原样来自词表，模型编出来的词在这里会被
 * {@link Keywords#intersect} 过滤掉。这样词表始终是权威的，不会被模型的临时发挥污染。
 *
 * <p><b>降级策略（与 personalrag 的 QueryRouter 一致）</b>：模型没返回、route 不合法、
 * 置信度低于 {@link #MIN_CONFIDENCE} —— 三种情况都降级到知识库检索。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    /** 低于这个置信度就不信模型的路由判断，降级走知识库 */
    private static final double MIN_CONFIDENCE = 0.5;

    private static final String PROMPT_PATH = "prompts/intent-recognition.st";

    private final Model intentModel;
    private final StructuredModelCaller structuredModelCaller;
    private final PromptLoader promptLoader;

    /**
     * 用查询改写那个轻量模型：意图识别是每次提问都要跑的前置步骤，
     * 用主对话模型既慢又贵，而这类"分类 + 选词"的任务轻模型够用。
     */
    public IntentClassifier(@Qualifier("queryRewriteModel") Model intentModel,
                            StructuredModelCaller structuredModelCaller,
                            PromptLoader promptLoader) {
        this.intentModel = intentModel;
        this.structuredModelCaller = structuredModelCaller;
        this.promptLoader = promptLoader;
    }

    /**
     * 判断这一轮该走哪条检索路径。
     *
     * @param query    用户问题（通常已经是改写后的）
     * @param history  最近对话，可为空
     * @param keywords 该用户的词表，可为空
     * @return 永远返回一个可用结果，最差是"降级走知识库"，不会返回 null
     */
    public IntentDecision classify(String query, String history, List<String> keywords) {
        if (query == null || query.isBlank()) {
            return IntentDecision.fallback("问题为空，按知识库处理");
        }
        List<String> wordList = keywords == null ? List.of() : keywords;
        String prompt = promptLoader.load(PROMPT_PATH, Map.of(
                "keywords", Keywords.joinForPrompt(wordList),
                "history", (history == null || history.isBlank()) ? "（无历史对话）" : history,
                "query", query));

        Optional<IntentDecision> parsed =
                structuredModelCaller.call(intentModel, prompt, IntentDecision.class);
        if (parsed.isEmpty()) {
            log.warn("[意图识别] 模型未返回可用结构化结果，降级为知识库检索: query=「{}」", query);
            return IntentDecision.fallback("模型未返回可用结果，降级到知识库检索");
        }

        IntentDecision decision = parsed.get();
        if (!decision.routeValid()) {
            log.warn("[意图识别] 路由取值非法({}), 降级为知识库检索: query=「{}」",
                    decision.route(), query);
            return decision.withRoute(Routes.RAG, "模型给出的路由非法，降级到知识库检索");
        }

        // 关键词必须来自词表：模型编出来的词直接丢掉，只保留真正命中的
        List<String> matched = Keywords.intersect(decision.matchedKeywords(), wordList);
        if (matched.size() != decision.matchedKeywords().size()) {
            log.info("[意图识别] 过滤掉不在词表里的关键词: {} -> {}",
                    decision.matchedKeywords(), matched);
        }
        IntentDecision normalized = new IntentDecision(
                decision.intent(), decision.route(), matched, decision.reasoning(), decision.confidence());

        if (normalized.confidence() < MIN_CONFIDENCE) {
            log.info("[意图识别] 置信度过低({}), 降级为知识库检索: query=「{}」, 原路由={}",
                    normalized.confidence(), query, normalized.route());
            return normalized.withRoute(Routes.RAG,
                    "置信度过低(" + normalized.confidence() + ")，降级到知识库检索");
        }

        log.info("[意图识别] route={}, intent={}, confidence={}, 命中关键词={}, query=「{}」",
                normalized.route(), normalized.intent(), normalized.confidence(),
                normalized.matchedKeywords(), query);
        return normalized;
    }
}

package com.tyut.agentscope.intent;

import java.util.List;

/**
 * <h2>意图识别结果</h2>
 *
 * <p>模型按 {@code prompts/intent-recognition.st} 的约定输出 JSON，Jackson 直接映射成这个
 * record——和 personalrag 里 {@code IntentRecognitionResult} / {@code RouterDecision} 的写法一致。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code intent}：问题核心意图，用于日志与界面展示；</li>
 *   <li>{@code route}：{@link Routes#RAG} 或 {@link Routes#WEB}，本轮走哪条检索路径；</li>
 *   <li>{@code matchedKeywords}：命中的词表关键词，只能来自用户的词表；</li>
 *   <li>{@code reasoning}：判断依据，一句话；</li>
 *   <li>{@code confidence}：0~1 的把握度，低于阈值会被降级。</li>
 * </ul>
 *
 * <p>紧凑构造器里做 null 与 NaN 兜底：模型少给字段时不能让下游 NPE。
 */
public record IntentDecision(
        String intent,
        String route,
        List<String> matchedKeywords,
        String reasoning,
        double confidence) {

    public IntentDecision {
        intent = (intent == null || intent.isBlank()) ? "未识别" : intent.trim();
        route = route == null ? "" : route.trim();
        matchedKeywords = matchedKeywords == null ? List.of() : List.copyOf(matchedKeywords);
        reasoning = reasoning == null ? "" : reasoning.trim();
        confidence = Double.isNaN(confidence) ? 0.0 : confidence;
    }

    /** 模型返回的 route 是否是合法取值 */
    public boolean routeValid() {
        return Routes.isValid(route);
    }

    /** 模型没给出可用结果时的兜底：走知识库（漏检还能再联网，反过来的代价更大） */
    public static IntentDecision fallback(String reason) {
        return new IntentDecision("未识别", Routes.RAG, List.of(), reason, 0.0);
    }

    /** 用户强制指定路由：不调模型，直接按用户的选择走 */
    public static IntentDecision forced(String route, String reason) {
        return new IntentDecision("用户指定", route, List.of(), reason, 1.0);
    }

    /** 换个 route 但保留其它判断信息，用于置信度过低时的降级 */
    public IntentDecision withRoute(String newRoute, String newReason) {
        return new IntentDecision(intent, newRoute, matchedKeywords, newReason, confidence);
    }
}

package com.tyut.agentscope.keyword;

import java.util.List;

/**
 * 关键词提炼的模型输出（{@code prompts/keyword-extract.st} 约定的 JSON）。
 *
 * <p>只做 null 兜底：真正的清洗（去空、去超长、按归一化去重）在
 * {@link Keywords#sanitize} 里做，模型给的东西不能直接信。
 */
public record KeywordExtraction(List<String> keywords) {

    public KeywordExtraction {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
}

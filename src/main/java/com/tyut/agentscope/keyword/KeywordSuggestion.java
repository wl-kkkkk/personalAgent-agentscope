package com.tyut.agentscope.keyword;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code extract_keywords} 返回给模型的结构化结果。
 *
 * <p>把"提炼"和"对比"一次做完，模型不用自己做集合运算：
 * <ul>
 *   <li>{@code candidates}：这篇文档的候选关键词（清洗后）；</li>
 *   <li>{@code existing}：其中词表里已经有了的；</li>
 *   <li>{@code added}：其中需要新增的（JSON 字段名用 {@code new}，Java 里是关键字）；</li>
 *   <li>{@code keywordsText}：顿号拼好的一串，方便模型原样复制给 {@code upload_document}。</li>
 * </ul>
 */
public record KeywordSuggestion(
        List<String> candidates,
        List<String> existing,
        @JsonProperty("new") List<String> added,
        String keywordsText) {

    public static KeywordSuggestion of(List<String> candidates, List<String> existing) {
        List<String> added = Keywords.difference(candidates, existing);
        return new KeywordSuggestion(
                List.copyOf(candidates),
                Keywords.intersect(candidates, existing),
                added,
                String.join("、", candidates));
    }
}

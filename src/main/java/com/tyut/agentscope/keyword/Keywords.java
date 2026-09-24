package com.tyut.agentscope.keyword;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <h2>关键词的清洗与归一化</h2>
 *
 * <p>关键词要当"词表"用，就必须先把写法收敛掉：{@code RAG} / {@code rag} / {@code ＲＡＧ}
 * 是同一个词，{@code 向量 检索} 和 {@code 向量检索} 也是。不收敛的话词表会被同义词撑爆，
 * 查询侧的匹配也会因为写法不一致而漏掉。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>归一化：NFKC（全角转半角）→ 去首尾空白 → 转小写 → 去掉内部空白；</li>
 *   <li>入库展示：NFKC → 去首尾空白 → 内部空白压成一个空格（保留用户原本的写法）;</li>
 *   <li>超过 {@link #MAX_LENGTH} 的丢弃——那已经不是关键词而是句子；</li>
 *   <li>按归一化结果去重并保持顺序；单批最多 {@link #MAX_BATCH} 个。</li>
 * </ul>
 */
public final class Keywords {

    /** 单个关键词的最大长度，超过就当成句子丢掉 */
    public static final int MAX_LENGTH = 32;

    /** 单批最多接受多少个关键词，防止一次把词表灌爆 */
    public static final int MAX_BATCH = 20;

    /** 一批关键词里最多挑几个参与意图识别，避免提示词膨胀 */
    public static final int MAX_FOR_PROMPT = 50;

    private Keywords() {
    }

    /** 归一化：用于比较、去重、建唯一索引 */
    public static String normalize(String keyword) {
        if (keyword == null) {
            return "";
        }
        return Normalizer.normalize(keyword, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    /** 展示用的清洗：保留原始写法，只做全角转半角、去首尾空白、压缩内部空白 */
    public static String display(String keyword) {
        if (keyword == null) {
            return "";
        }
        return Normalizer.normalize(keyword, Normalizer.Form.NFKC)
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * 清洗一批关键词：去空、去超长、按归一化去重，保持输入顺序。
     *
     * @return 可直接入库的展示形式列表；入参为空时返回空列表，不是 null
     */
    public static List<String> sanitize(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Map<String, String> deduped = new LinkedHashMap<>();
        for (String item : raw) {
            String shown = display(item);
            if (shown.isEmpty() || shown.length() > MAX_LENGTH) {
                continue;
            }
            String key = normalize(shown);
            if (key.isEmpty()) {
                continue;
            }
            deduped.putIfAbsent(key, shown);
            if (deduped.size() >= MAX_BATCH) {
                break;
            }
        }
        return List.copyOf(deduped.values());
    }

    /** 归一化之后的集合，便于做交并差 */
    public static List<String> normalizedKeys(Collection<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(keywords.size());
        for (String keyword : keywords) {
            String key = normalize(keyword);
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 保留 candidates 中"命中 allowed"的那些，按 allowed 的顺序返回 */
    public static List<String> intersect(Collection<String> candidates, Collection<String> allowed) {
        if (candidates == null || candidates.isEmpty() || allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        List<String> candidateKeys = normalizedKeys(candidates);
        List<String> hits = new ArrayList<>();
        for (String item : allowed) {
            String key = normalize(item);
            if (!key.isEmpty() && candidateKeys.contains(key) && !hits.contains(item)) {
                hits.add(item);
            }
        }
        return List.copyOf(hits);
    }

    /** candidates 中不在 existing 里的部分（按归一化比较），按输入顺序返回 */
    public static List<String> difference(Collection<String> candidates, Collection<String> existing) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<String> existingKeys = normalizedKeys(existing);
        List<String> addedKeys = new ArrayList<>();
        List<String> diff = new ArrayList<>();
        for (String item : candidates) {
            String key = normalize(item);
            if (key.isEmpty() || existingKeys.contains(key) || addedKeys.contains(key)) {
                continue;
            }
            addedKeys.add(key);
            diff.add(item);
        }
        return List.copyOf(diff);
    }

    /**
     * 把模型或用户给的一串关键词文本拆成列表。
     * 支持顿号、逗号、分号、竖线、换行以及中英文标点混用。
     */
    public static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String cleaned = text.replaceAll("[\\[\\]\"']", " ");
        List<String> parts = new ArrayList<>();
        for (String part : cleaned.split("[、,，;；|\\n\\r\\t]+")) {
            String trimmed = display(part);
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return sanitize(parts);
    }

    /** 拼成提示词里用的清单文本 */
    public static String joinForPrompt(Collection<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return "（词表为空）";
        }
        List<String> limited = keywords.size() > MAX_FOR_PROMPT
                ? new ArrayList<>(keywords).subList(0, MAX_FOR_PROMPT)
                : new ArrayList<>(keywords);
        return String.join("、", limited);
    }

    /**
     * 从工具入参里取关键词。
     *
     * <p>模型传参很不稳定：可能给数组、可能给顿号分隔的字符串、字段名也可能变
     * （{@code keywords} / {@code keywordsText} / {@code keyword} / {@code tags}），
     * 所以这里逐个兜底，能取到就取，取不到返回空列表——不要因为少个字段就放弃整个上传。
     *
     * @param input 工具调用入参（可为 null）
     */
    public static List<String> fromInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        for (String key : List.of("keywords", "keywordsText", "keyword", "tags")) {
            Object value = input.get(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Collection<?> collection) {
                List<String> items = new ArrayList<>(collection.size());
                for (Object item : collection) {
                    if (item != null) {
                        items.add(String.valueOf(item));
                    }
                }
                List<String> cleaned = sanitize(items);
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            } else {
                List<String> cleaned = split(String.valueOf(value));
                if (!cleaned.isEmpty()) {
                    return cleaned;
                }
            }
        }
        return List.of();
    }
}

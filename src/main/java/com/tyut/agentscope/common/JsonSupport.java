package com.tyut.agentscope.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * <h2>结构化输出的容错解析</h2>
 *
 * <p>让模型"只输出 JSON"是提示词层面的事，模型偶尔还是会带上解释、包一层 ```json、或者在
 * JSON 后面再补一句。直接 {@code readValue} 就会失败，所以这里先做两步兜底：
 * <ol>
 *   <li>剥掉 markdown 代码块围栏；</li>
 *   <li>从第一个 <code>{</code> 开始做括号配对扫描，取出完整的 JSON 对象（跳过字符串里的括号和转义）。</li>
 * </ol>
 *
 * <p>解析失败**不抛异常**，返回 {@link Optional#empty()}，由调用方决定降级策略——
 * 意图识别这类前置步骤失败了应该走默认路由，而不是把整轮对话打断。
 */
@Component
public class JsonSupport {

    private static final Logger log = LoggerFactory.getLogger(JsonSupport.class);

    private final ObjectMapper objectMapper;

    public JsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析成指定类型；内容为空、没有 JSON、或字段对不上都返回 empty */
    public <T> Optional<T> parse(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            log.warn("结构化输出为空，无法解析成 {}", type.getSimpleName());
            return Optional.empty();
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            log.warn("结构化输出里没有找到完整 JSON 对象: {}", abbreviate(raw));
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(json, type));
        } catch (Exception e) {
            log.warn("结构化输出解析失败: error={}, raw={}", e.getMessage(), abbreviate(raw));
            return Optional.empty();
        }
    }

    /**
     * 从一段可能带杂质的文本里取出第一个完整的 JSON 对象。
     *
     * @return 去掉代码块围栏后的 JSON 文本；找不到配对的括号时返回 null
     */
    public String extractJsonObject(String raw) {
        String text = stripCodeFence(raw);
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    /** 去掉 ``` 或 ```json 围栏，只留里面的内容 */
    private String stripCodeFence(String raw) {
        String text = raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return text;
        }
        String body = text.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        return (closing >= 0 ? body.substring(0, closing) : body).trim();
    }

    private String abbreviate(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}

package com.tyut.agentscope.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * <h2>提示词加载器</h2>
 * <p>
 * 从 classpath 读取 {@code .st} 提示词模板，替换其中的 {@code {{key}}} 占位符。
 * 占位符对应的值为 {@code null} 时替换为空串，避免把 "null" 拼进提示词。
 *
 * <h3>边界处理</h3>
 * <ul>
 *   <li>路径为空：直接抛 {@link IllegalArgumentException}；</li>
 *   <li>文件不存在：记 error 日志后抛 {@link IllegalStateException}，不返回半成品提示词；</li>
 *   <li>读取失败：包装成 {@link UncheckedIOException} 抛出，保留原始异常链。</li>
 * </ul>
 */
@Component
public class PromptLoader {

    private static final Logger log = LoggerFactory.getLogger(PromptLoader.class);

    public String load(String path, Map<String, Object> variables) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("提示词路径不能为空");
        }
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.error("提示词文件不存在: {}", path);
            throw new IllegalStateException("提示词文件不存在: " + path);
        }
        try {
            String template = resource.getContentAsString(StandardCharsets.UTF_8);
            if (variables != null) {
                for (Map.Entry<String, Object> entry : variables.entrySet()) {
                    Object value = entry.getValue();
                    template = template.replace("{{" + entry.getKey() + "}}",
                            value == null ? "" : String.valueOf(value));
                }
            }
            log.debug("已加载提示词: {} ({} 字符)", path, template.length());
            return template;
        } catch (IOException e) {
            log.error("读取提示词失败: {}", path, e);
            throw new UncheckedIOException("读取提示词失败: " + path, e);
        }
    }
}

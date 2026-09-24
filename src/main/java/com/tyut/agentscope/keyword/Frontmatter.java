package com.tyut.agentscope.keyword;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * <h2>把关键词写进 Markdown 的 YAML frontmatter</h2>
 *
 * <pre>
 * ---
 * keywords: [RAG, 向量检索]
 * ---
 *
 * # 正文标题
 * ...
 * </pre>
 *
 * <p><b>为什么不正则替换正文</b>：正文是模型写的，里面什么字符都可能有，用正则改一处很容易踩到
 * 别的地方（标题里的 #、代码块里的 ---、CRLF）。这里只在**文件开头的 frontmatter 区块**里操作：
 * 有就替换其中的 {@code keywords:} 行（其它字段如 title/source 保留），没有就整体加一个区块。
 *
 * <p>换行统一成 {@code \n}：Markdown 文件用哪种换行无所谓，但混着写容易出难查的问题。
 */
public final class Frontmatter {

    private static final String FENCE = "---";
    private static final String KEY = "keywords:";

    private Frontmatter() {
    }

    /**
     * 给正文加上（或更新）关键词 frontmatter。
     *
     * @param content  原始正文，可为 null
     * @param keywords 关键词；为空时原样返回，不动正文
     * @return 加了 frontmatter 的正文
     */
    public static String apply(String content, Collection<String> keywords) {
        if (content == null) {
            return null;
        }
        List<String> cleaned = Keywords.sanitize(keywords);
        if (cleaned.isEmpty()) {
            return content;
        }
        String line = KEY + " [" + String.join(", ", cleaned) + "]";
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');

        List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
        int closing = closingFenceIndex(lines);
        if (closing > 0) {
            // 已有 frontmatter：只替换 keywords 行，其它字段保留
            List<String> body = new ArrayList<>();
            for (int i = 1; i < closing; i++) {
                if (!lines.get(i).trim().toLowerCase().startsWith(KEY)) {
                    body.add(lines.get(i));
                }
            }
            body.add(line);

            StringBuilder rebuilt = new StringBuilder(FENCE).append('\n');
            body.forEach(item -> rebuilt.append(item).append('\n'));
            rebuilt.append(FENCE).append('\n');
            for (int i = closing + 1; i < lines.size(); i++) {
                rebuilt.append(lines.get(i));
                if (i < lines.size() - 1) {
                    rebuilt.append('\n');
                }
            }
            return rebuilt.toString();
        }
        return FENCE + "\n" + line + "\n" + FENCE + "\n\n" + normalized;
    }

    /**
     * 找出开头 frontmatter 区块的结束围栏行号。
     *
     * @return 结束围栏的行号；没有完整 frontmatter 时返回 -1
     */
    private static int closingFenceIndex(List<String> lines) {
        if (lines.size() < 2 || !FENCE.equals(lines.get(0).trim())) {
            return -1;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (FENCE.equals(lines.get(i).trim())) {
                return i;
            }
        }
        return -1;
    }

    /** 从正文里取出现有的关键词（frontmatter 里那行），解析失败返回空列表 */
    public static List<String> read(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> lines = List.of(content.replace("\r\n", "\n").split("\n", -1));
        int closing = closingFenceIndex(lines);
        if (closing <= 0) {
            return List.of();
        }
        for (int i = 1; i < closing; i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.toLowerCase().startsWith(KEY)) {
                String value = trimmed.substring(KEY.length()).trim();
                value = value.replaceAll("^\\[", "").replaceAll("]$", "");
                return Keywords.split(value);
            }
        }
        return List.of();
    }
}

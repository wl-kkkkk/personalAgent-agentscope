package com.tyut.agentscope;

import com.tyut.agentscope.keyword.Frontmatter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关键词写进 Markdown frontmatter：只动开头那个 frontmatter 区块，不碰正文。
 */
class FrontmatterTest {

    @Test
    void prependsFrontmatterWhenAbsent() {
        String result = Frontmatter.apply("# 标题\n\n正文", List.of("RAG", "向量检索"));

        assertThat(result).isEqualTo("""
                ---
                keywords: [RAG, 向量检索]
                ---

                # 标题

                正文""");
    }

    @Test
    void keepsOtherFrontmatterFieldsAndReplacesKeywordsLine() {
        String content = """
                ---
                title: 旧标题
                keywords: [旧词]
                source: https://example.com
                ---

                # 正文""";

        String result = Frontmatter.apply(content, List.of("新词"));

        assertThat(result).contains("title: 旧标题");
        assertThat(result).contains("source: https://example.com");
        assertThat(result).contains("keywords: [新词]");
        assertThat(result).doesNotContain("旧词");
        assertThat(result).endsWith("# 正文");
        // 只有一个 frontmatter 区块：竖线围栏恰好两行
        assertThat(result.lines().filter("---"::equals).count()).isEqualTo(2);
    }

    @Test
    void addsKeywordsIntoExistingFrontmatterThatHadNone() {
        String content = """
                ---
                title: 标题
                ---

                正文""";

        String result = Frontmatter.apply(content, List.of("RAG"));

        assertThat(result).isEqualTo("""
                ---
                title: 标题
                keywords: [RAG]
                ---

                正文""");
    }

    @Test
    void emptyKeywordsLeaveContentUntouched() {
        String content = "# 标题\n正文";

        assertThat(Frontmatter.apply(content, List.of())).isEqualTo(content);
        assertThat(Frontmatter.apply(content, null)).isEqualTo(content);
        assertThat(Frontmatter.apply(null, List.of("RAG"))).isNull();
    }

    @Test
    void crlfContentIsNormalized() {
        String result = Frontmatter.apply("---\r\ntitle: t\r\n---\r\n正文", List.of("RAG"));

        assertThat(result).isEqualTo("""
                ---
                title: t
                keywords: [RAG]
                ---
                正文""");
        assertThat(result).doesNotContain("\r");
    }

    @Test
    void bodyRulesAreNotMistakenForFrontmatter() {
        String content = "# 标题\n\n---\n\n正文里的分隔线";

        String result = Frontmatter.apply(content, List.of("RAG"));

        assertThat(result).startsWith("---\nkeywords: [RAG]\n---\n\n# 标题");
        assertThat(result).contains("正文里的分隔线");
    }

    @Test
    void readReturnsKeywordsWrittenByApply() {
        String applied = Frontmatter.apply("# 标题", List.of("RAG", "MCP"));

        assertThat(Frontmatter.read(applied)).containsExactly("RAG", "MCP");
        assertThat(Frontmatter.read("# 没有 frontmatter")).isEmpty();
        assertThat(Frontmatter.read(null)).isEmpty();
    }
}

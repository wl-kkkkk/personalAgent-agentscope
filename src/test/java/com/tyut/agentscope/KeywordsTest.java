package com.tyut.agentscope;

import com.tyut.agentscope.keyword.Keywords;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词表归一化：不依赖 DB / LLM。
 *
 * <p>重点是"同一个词的多种写法必须收敛到一个 key"，否则词表会被同义词撑爆，
 * 意图匹配也会因为大小写不同而漏掉。
 */
class KeywordsTest {

    @Test
    void normalizeUnifiesCaseFullWidthAndSpaces() {
        assertThat(Keywords.normalize("RAG")).isEqualTo("rag");
        assertThat(Keywords.normalize("  rag  ")).isEqualTo("rag");
        assertThat(Keywords.normalize("ＲＡＧ")).isEqualTo("rag");
        assertThat(Keywords.normalize("向量 检索")).isEqualTo("向量检索");
    }

    @Test
    void sanitizeDropsBlankAndOverlongAndKeepsFirstSpelling() {
        List<String> cleaned = Keywords.sanitize(List.of(
                "RAG", "  ", "rag", "向量检索", "x".repeat(Keywords.MAX_LENGTH + 1)));

        // RAG 与 rag 归一化后相同，只保留先出现的写法；超长项被当成句子丢掉
        assertThat(cleaned).containsExactly("RAG", "向量检索");
    }

    @Test
    void splitHandlesChineseAndEnglishSeparators() {
        assertThat(Keywords.split("RAG、向量检索，MCP;知识库"))
                .containsExactly("RAG", "向量检索", "MCP", "知识库");
        assertThat(Keywords.split("[\"RAG\", \"MCP\"]"))
                .containsExactly("RAG", "MCP");
        assertThat(Keywords.split("   ")).isEmpty();
    }

    @Test
    void intersectOnlyKeepsWordsThatExistInTheLibrary() {
        List<String> library = List.of("RAG", "向量检索", "MySQL");

        // 模型编出来的"知识图谱"不在词表里，必须被过滤掉
        assertThat(Keywords.intersect(List.of("rag", "知识图谱"), library))
                .containsExactly("RAG");
        assertThat(Keywords.intersect(List.of("RAG", "向量检索", "MCP"), library))
                .containsExactly("RAG", "向量检索");
        assertThat(Keywords.intersect(List.of(), library)).isEmpty();
    }

    @Test
    void differenceReturnsOnlyNewWords() {
        List<String> existing = List.of("RAG");

        assertThat(Keywords.difference(List.of("RAG", "MCP", "mcp"), existing))
                .containsExactly("MCP");
    }

    @Test
    void joinForPromptMarksEmptyLibrary() {
        assertThat(Keywords.joinForPrompt(List.of())).isEqualTo("（词表为空）");
        assertThat(Keywords.joinForPrompt(List.of("RAG", "MCP"))).isEqualTo("RAG、MCP");
    }

    @Test
    void fromInputAcceptsArrayStringAndAlternateFieldNames() {
        // 模型可能给数组
        assertThat(Keywords.fromInput(Map.of("keywords", List.of("RAG", "MCP"))))
                .containsExactly("RAG", "MCP");
        // 也可能给顿号串
        assertThat(Keywords.fromInput(Map.of("keywords", "RAG、MCP")))
                .containsExactly("RAG", "MCP");
        // 字段名也不稳定
        assertThat(Keywords.fromInput(Map.of("keywordsText", "RAG")))
                .containsExactly("RAG");
        assertThat(Keywords.fromInput(Map.of("tags", List.of("RAG"))))
                .containsExactly("RAG");
        // 都没有就返回空，不要因此让整个上传流程失败
        assertThat(Keywords.fromInput(Map.of("title", "只有标题"))).isEmpty();
        assertThat(Keywords.fromInput(null)).isEmpty();
    }
}

package com.tyut.agentscope;

import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 skills/ 目录下的 Markdown 能被解析加载（不依赖 LLM / MCP）。
 */
class SkillLoadTest {

    @Test
    void loadsSkillMarkdownFromClasspath() throws IOException {
        ClasspathSkillRepository repository = new ClasspathSkillRepository("skills");
        assertThat(repository.getAllSkillNames())
                .isNotEmpty()
                .hasSize(2);
    }
}

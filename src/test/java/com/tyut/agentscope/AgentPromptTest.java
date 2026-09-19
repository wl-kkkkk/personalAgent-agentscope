package com.tyut.agentscope;

import com.tyut.agentscope.common.PromptLoader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验系统提示词的占位符与 KnowledgeAgentFactory 传参一致：
 * 换了占位符名却忘了改传参，会静默变成"（未知）"，很难发现。
 */
class AgentPromptTest {

    @Test
    void rendersNicknameAndRootFolder() {
        String prompt = new PromptLoader().load("prompts/agent-system.st", Map.of(
                "nickname", "测试用户",
                "rootFolder", "D:/Java/personalproject/personalAgentDoc"));

        assertThat(prompt)
                .contains("测试用户")
                .contains("D:/Java/personalproject/personalAgentDoc")
                .doesNotContain("{{nickname}}")
                .doesNotContain("{{rootFolder}}");
    }
}

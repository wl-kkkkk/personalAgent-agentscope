package com.tyut.agentscope;

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 userId:sessionId 这个 key 规则能达到隔离效果（不依赖 DB / LLM）。
 */
class SessionKeyTest {

    @Test
    void sameSessionDifferentUsersDoNotCollide() {
        SessionKey userA = SimpleSessionKey.of("u1:s1");
        SessionKey userB = SimpleSessionKey.of("u2:s1");

        assertThat(userA.toIdentifier()).contains("u1");
        assertThat(userA.toIdentifier()).isNotEqualTo(userB.toIdentifier());
    }

    @Test
    void differentSessionsOfSameUserDoNotCollide() {
        SessionKey first = SimpleSessionKey.of("u1:s1");
        SessionKey second = SimpleSessionKey.of("u1:s2");

        assertThat(first.toIdentifier()).isNotEqualTo(second.toIdentifier());
    }
}

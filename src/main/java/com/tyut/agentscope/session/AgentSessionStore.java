package com.tyut.agentscope.session;

import io.agentscope.core.session.Session;
import io.agentscope.core.session.mysql.MysqlSession;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * 第二层：会话持久化。
 *
 * 存储用框架的 MysqlSession（默认表名 agentscope_sessions，不存在会自动建）；
 * session key 规则为 userId:sessionId，保证不同用户、不同会话互相隔离。
 */
@Component
public class AgentSessionStore {

    private static final Logger log = LoggerFactory.getLogger(AgentSessionStore.class);
    private static final int MAX_SESSION_ID_LENGTH = 255;

    private final Session session;

    /**
     * 注意：MysqlSession 的默认库名是 {@code agentscope}（框架自己建的），
     * 会和本项目其他表（user_info / agent_*）分在两个库里。
     * 这里显式指定成本项目的库，让所有表落在一起。
     */
    public AgentSessionStore(DataSource dataSource,
                             @Value("${app.session.database:personalagent}") String database,
                             @Value("${app.session.table:agentscope_sessions}") String table) {
        log.info("会话记忆将使用 {}.{}", database, table);
        this.session = new MysqlSession(dataSource, database, table, true);
    }

    public Session session() {
        return session;
    }

    /** sessionId 由调用方回传；没传就用新 UUID（说明是新会话） */
    public String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            String generated = UUID.randomUUID().toString();
            log.info("未传 sessionId，生成新会话: {}", generated);
            return generated;
        }
        String trimmed = sessionId.trim();
        // 提前按框架的校验规则挡掉，给出可读的错误信息（框架只会抛 IllegalArgumentException）
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("sessionId 不能包含路径分隔符: " + trimmed);
        }
        if (trimmed.length() > MAX_SESSION_ID_LENGTH) {
            throw new IllegalArgumentException("sessionId 不能超过 " + MAX_SESSION_ID_LENGTH + " 个字符");
        }
        return trimmed;
    }

    public SessionKey key(String userId, String sessionId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return SimpleSessionKey.of(userId + ":" + sessionId);
    }
}

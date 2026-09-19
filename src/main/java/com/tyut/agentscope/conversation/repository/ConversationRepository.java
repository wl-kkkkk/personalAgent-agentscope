package com.tyut.agentscope.conversation.repository;

import com.tyut.agentscope.conversation.entity.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * agent_conversation 表的读写。建表语句见 docs/sql/init.sql。
 */
@Repository
public class ConversationRepository {

    private static final Logger log = LoggerFactory.getLogger(ConversationRepository.class);

    private static final RowMapper<Conversation> MAPPER = (rs, rowNum) -> new Conversation(
            rs.getString("conversation_id"),
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(String conversationId, String userId, String title) {
        jdbcTemplate.update("""
                INSERT INTO agent_conversation (conversation_id, user_id, title)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE updated_at = NOW()
                """, conversationId, userId, title);
    }

    public Optional<Conversation> findById(String conversationId) {
        return jdbcTemplate.query(
                        "SELECT * FROM agent_conversation WHERE conversation_id = ?", MAPPER, conversationId)
                .stream().findFirst();
    }

    public List<Conversation> findByUser(String userId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_conversation
                 WHERE user_id = ?
                 ORDER BY updated_at DESC
                 LIMIT 50
                """, MAPPER, userId);
    }

    public int updateTitle(String conversationId, String title) {
        int updated = jdbcTemplate.update(
                "UPDATE agent_conversation SET title = ? WHERE conversation_id = ?",
                title, conversationId);
        log.info("会话标题已更新: conversationId={}, title={}", conversationId, title);
        return updated;
    }

    public int delete(String conversationId) {
        return jdbcTemplate.update(
                "DELETE FROM agent_conversation WHERE conversation_id = ?", conversationId);
    }

    /** 每轮对话更新一下时间，让列表按最近使用排序 */
    public void touch(String conversationId) {
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = NOW() WHERE conversation_id = ?",
                conversationId);
    }
}

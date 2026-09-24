package com.tyut.agentscope.keyword;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code agent_keyword} 表的读写：一个用户一张词表。
 *
 * <p>唯一键是 {@code (user_id, normalized)}，所以同一写法的重复插入会被数据库挡掉，
 * 并发下也不会写进两条。建表语句见 {@code docs/sql/init.sql}。
 */
@Repository
public class KeywordRepository {

    private static final RowMapper<String> KEYWORD_MAPPER = (rs, rowNum) -> rs.getString("keyword");

    private final JdbcTemplate jdbcTemplate;

    public KeywordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按创建时间返回该用户的全部关键词（先来的先展示，词表顺序稳定） */
    public List<String> listByUser(String userId) {
        return jdbcTemplate.query("""
                        SELECT keyword FROM agent_keyword
                         WHERE user_id = ?
                         ORDER BY id
                        """,
                KEYWORD_MAPPER, userId);
    }

    /**
     * 插入一个关键词；已存在（归一化后相同）则忽略。
     *
     * @return 真正新插入的行数：1 表示新增，0 表示本来就有
     */
    public int insertIgnore(String userId, String keyword, String normalized, String source) {
        return jdbcTemplate.update("""
                        INSERT IGNORE INTO agent_keyword (user_id, keyword, normalized, source)
                        VALUES (?, ?, ?, ?)
                        """,
                userId, keyword, normalized, source);
    }

    /** 按归一化删词 */
    public int deleteByNormalized(String userId, String normalized) {
        return jdbcTemplate.update(
                "DELETE FROM agent_keyword WHERE user_id = ? AND normalized = ?",
                userId, normalized);
    }
}

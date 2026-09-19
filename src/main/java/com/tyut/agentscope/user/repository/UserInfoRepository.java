package com.tyut.agentscope.user.repository;

import com.tyut.agentscope.user.entity.UserInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

/**
 * <h2>用户仓储</h2>
 *
 * <p>基于 JdbcTemplate 的 user_info 表读写。表结构见 {@code docs/sql/init.sql}。
 */
@Repository
public class UserInfoRepository {

    private static final Logger log = LoggerFactory.getLogger(UserInfoRepository.class);

    private static final RowMapper<UserInfo> MAPPER = (rs, rowNum) -> new UserInfo(
            rs.getLong("id"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getString("password"),
            rs.getString("nickname"),
            rs.getString("status"),
            rs.getString("root_folder"));

    private final JdbcTemplate jdbcTemplate;

    public UserInfoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<UserInfo> findByPhone(String phone) {
        return jdbcTemplate.query("SELECT * FROM user_info WHERE phone = ?", MAPPER, phone)
                .stream().findFirst();
    }

    public Optional<UserInfo> findById(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("SELECT * FROM user_info WHERE id = ?", MAPPER, userId)
                .stream().findFirst();
    }

    public long insert(String phone, String passwordHash, String nickname) {
        // 注意：不要用 SimpleJdbcInsert —— 它默认按整张表的列拼 INSERT，
        // map 里没给的列会显式写成 NULL，会撞上 created_at 的 NOT NULL DEFAULT。
        // 这里显式列出要写入的列，其余交给数据库默认值。
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO user_info (phone, password, nickname, status)
                    VALUES (?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, phone);
            ps.setString(2, passwordHash);
            ps.setString(3, nickname);
            ps.setString(4, UserInfo.STATUS_ACTIVE);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("注册失败：数据库没有返回自增主键");
        }
        long userId = key.longValue();
        log.info("用户注册成功: userId={}, phone={}", userId, phone);
        return userId;
    }

    public int updateRootFolder(String userId, String rootFolder) {
        int updated = jdbcTemplate.update(
                "UPDATE user_info SET root_folder = ? WHERE id = ?", rootFolder, userId);
        log.info("用户 {} 的根目录已更新为 {}, 影响行数={}", userId, rootFolder, updated);
        return updated;
    }
}

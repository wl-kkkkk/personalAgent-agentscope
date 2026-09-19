package com.tyut.agentscope.library.repository;

import com.tyut.agentscope.library.entity.MarkdownFileMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <h2>Markdown 元信息仓储</h2>
 *
 * <p>按 (userId, path_hash) 做 upsert，保证反复同步不会产生重复行、文件 id 也保持稳定；
 * 同步完再删掉本次没扫到的记录。
 */
@Repository
public class MarkdownFileRepository {

    private static final Logger log = LoggerFactory.getLogger(MarkdownFileRepository.class);

    private static final RowMapper<MarkdownFileMeta> MAPPER = (rs, rowNum) -> new MarkdownFileMeta(
            rs.getLong("id"),
            rs.getString("user_id"),
            rs.getString("root_folder"),
            rs.getString("relative_path"),
            rs.getString("absolute_path"),
            rs.getString("file_name"),
            rs.getLong("file_size"),
            rs.getTimestamp("last_modified") == null ? null : rs.getTimestamp("last_modified").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public MarkdownFileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<MarkdownFileMeta> findByUser(String userId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_markdown_file
                 WHERE user_id = ?
                 ORDER BY relative_path
                """, MAPPER, userId);
    }

    public Optional<MarkdownFileMeta> findByIdAndUser(long id, String userId) {
        return jdbcTemplate.query("""
                        SELECT * FROM agent_markdown_file WHERE id = ? AND user_id = ?
                        """, MAPPER, id, userId)
                .stream().findFirst();
    }

    public void upsert(MarkdownFileMeta meta, String pathHash) {
        jdbcTemplate.update("""
                        INSERT INTO agent_markdown_file
                            (user_id, root_folder, relative_path, absolute_path, path_hash,
                             file_name, file_size, last_modified)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                            root_folder   = VALUES(root_folder),
                            relative_path = VALUES(relative_path),
                            file_name     = VALUES(file_name),
                            file_size     = VALUES(file_size),
                            last_modified = VALUES(last_modified)
                        """,
                meta.userId(), meta.rootFolder(), meta.relativePath(), meta.absolutePath(), pathHash,
                meta.fileName(), meta.fileSize(),
                meta.lastModified() == null ? null : Timestamp.valueOf(meta.lastModified()));
    }

    /** 删掉本次同步没扫到的记录；keepHashes 为空表示该目录下已无文件。 */
    public int deleteMissing(String userId, List<String> keepHashes) {
        if (keepHashes == null || keepHashes.isEmpty()) {
            return deleteByUser(userId);
        }
        List<String> placeholders = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        for (String hash : keepHashes) {
            placeholders.add("?");
            args.add(hash);
        }
        int removed = jdbcTemplate.update(
                "DELETE FROM agent_markdown_file WHERE user_id = ? AND path_hash NOT IN ("
                        + String.join(",", placeholders) + ")",
                args.toArray());
        if (removed > 0) {
            log.info("同步时清理掉 {} 条已不存在的文件记录, userId={}", removed, userId);
        }
        return removed;
    }

    public int deleteByUser(String userId) {
        return jdbcTemplate.update("DELETE FROM agent_markdown_file WHERE user_id = ?", userId);
    }
}

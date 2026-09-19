package com.tyut.agentscope.hitl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * agent_hitl_task 表的读写。建表语句见 docs/sql/agent_hitl_task.sql。
 */
@Repository
public class HitlTaskRepository {

    private static final RowMapper<HitlTask> MAPPER = (rs, rowNum) -> new HitlTask(
            rs.getString("task_id"),
            rs.getString("user_id"),
            rs.getString("session_id"),
            rs.getString("tool_use_id"),
            rs.getString("tool_name"),
            rs.getString("tool_input"),
            rs.getString("status"),
            rs.getString("decision_note"));

    private final JdbcTemplate jdbcTemplate;

    public HitlTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public HitlTask create(String userId, String sessionId, String toolUseId, String toolName, String toolInput) {
        HitlTask task = new HitlTask(UUID.randomUUID().toString(), userId, sessionId,
                toolUseId, toolName, toolInput, HitlTask.PENDING, null);
        jdbcTemplate.update("""
                        INSERT INTO agent_hitl_task
                            (task_id, user_id, session_id, tool_use_id, tool_name, tool_input, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                task.taskId(), task.userId(), task.sessionId(),
                task.toolUseId(), task.toolName(), task.toolInput(), task.status());
        return task;
    }

    public Optional<HitlTask> findByTaskId(String taskId) {
        return jdbcTemplate.query(
                        "SELECT * FROM agent_hitl_task WHERE task_id = ?", MAPPER, taskId)
                .stream().findFirst();
    }

    public void updateDecision(String taskId, String status, String note) {
        jdbcTemplate.update("""
                        UPDATE agent_hitl_task
                           SET status = ?, decision_note = ?, decided_at = NOW()
                         WHERE task_id = ? AND status = ?
                        """,
                status, note, taskId, HitlTask.PENDING);
    }

    /** 会话删除时，把它下面挂起的审批任务一并清掉，避免留下孤儿任务 */
    public int deleteBySession(String userId, String sessionId) {
        return jdbcTemplate.update(
                "DELETE FROM agent_hitl_task WHERE user_id = ? AND session_id = ?",
                userId, sessionId);
    }
}

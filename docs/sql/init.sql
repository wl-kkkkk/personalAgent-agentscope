-- ============================================================================
-- personalAgent / agentscope 模块建库建表脚本
--
-- 表说明：
--   user_info           用户表（结构对齐 personalrag.user_info，额外加了 root_folder）
--   agent_markdown_file Markdown 文件元信息：只存路径与大小等信息，正文在本地文件里
--   agent_hitl_task     人工审批（HITL）任务表
--
-- 不在这里建的表：
--   （无）—— agentscope_sessions 也列在下面，方便你统一管理；
--   它同时会被 AgentScope 的 MysqlSession 自动创建（构造参数 createIfNotExist=true），
--   两处 DDL 完全一致，谁先执行都行
--
-- 执行方式：
--   mysql -h 192.168.141.129 -u myuser -p --default-character-set=utf8mb4 -e "source D:/Java/personalproject/personalAgent/agentscope/docs/sql/init.sql"
--   执行账号需要 CREATE DATABASE 权限；没有的话让 DBA 先建库，或把 AGENT_DB_URL 指到已有权限的库
--
-- 脚本可重复执行（全部 IF NOT EXISTS）
-- ============================================================================

CREATE DATABASE IF NOT EXISTS `personalagent`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `personalagent`;

-- ----------------------------------------------------------------------------
-- 用户表
--   与 personalrag.user_info 结构一致，便于将来合并成单点登录；
--   多出的 root_folder 保存用户在界面上选定的 Markdown 根目录。
--   密码存 BCrypt 哈希，不存明文。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_info`
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    phone       VARCHAR(20)  NOT NULL COMMENT '手机号（登录账号）',
    email       VARCHAR(128) NULL COMMENT '邮箱',
    password    VARCHAR(128) NOT NULL COMMENT '登录密码（BCrypt 哈希）',
    nickname    VARCHAR(128) NULL COMMENT '昵称',
    status      VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE-正常 / FROZEN-冻结',
    root_folder VARCHAR(512) NULL COMMENT '用户选定的 Markdown 根目录（绝对路径）',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户信息表';

-- ----------------------------------------------------------------------------
-- Markdown 文件元信息表
--   只存元信息，正文不落库：数据库这行指向本地文件，界面上打开时按绝对路径读取。
--   换根目录时：更新 user_info.root_folder -> 清掉该用户旧记录 -> 按新目录重新同步。
--
--   path_hash 是绝对路径的 SHA-256，用它做唯一键，避免超长路径进索引（MySQL 索引长度限制）。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `agent_markdown_file`
(
    id            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id       VARCHAR(64)   NOT NULL COMMENT '所属用户ID（user_info.id）',
    root_folder   VARCHAR(512)  NOT NULL COMMENT '同步时使用的根目录',
    relative_path VARCHAR(1024) NOT NULL COMMENT '相对根目录的路径',
    absolute_path VARCHAR(1024) NOT NULL COMMENT '本地文件绝对路径',
    path_hash     CHAR(64)      NOT NULL COMMENT '绝对路径的 SHA-256（唯一索引用）',
    file_name     VARCHAR(255)  NOT NULL COMMENT '文件名',
    file_size     BIGINT        NOT NULL DEFAULT 0 COMMENT '文件字节数',
    last_modified DATETIME      NULL COMMENT '文件最后修改时间',
    synced_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近同步时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_path` (`user_id`, `path_hash`),
    KEY `idx_user` (`user_id`),
    KEY `idx_last_modified` (`last_modified`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='Markdown 文件元信息表';

-- ----------------------------------------------------------------------------
-- 会话记忆表（AgentScope 的 MysqlSession 使用）
--   默认库名是 agentscope，会和其它表分在两个库；AgentSessionStore 里已显式改成
--   本项目的库，所以这张表就建在这里。框架自己也会按同样的 DDL 自动建（createIfNotExist），
--   这段只是让你能统一管理、也能提前手工建好。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `agentscope_sessions`
(
    session_id VARCHAR(255) NOT NULL,
    state_key  VARCHAR(255) NOT NULL,
    item_index INT          NOT NULL DEFAULT 0,
    state_data LONGTEXT     NOT NULL,
    created_at DATETIME              DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (session_id, state_key, item_index)
) DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci COMMENT ='AgentScope 会话记忆表';

-- ----------------------------------------------------------------------------
-- 会话表
--   会话记忆本体在 agentscope_sessions（框架管），这张表只存"界面要展示的元信息"：
--   标题、创建/更新时间。标题先落一个临时值，再异步用轻量模型生成后回写。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `agent_conversation`
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    conversation_id VARCHAR(255) NOT NULL COMMENT '会话ID（等于 agent 的 sessionId）',
    user_id         VARCHAR(64)  NOT NULL COMMENT '所属用户ID',
    title           VARCHAR(255) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_conversation_id` (`conversation_id`),
    KEY `idx_user_updated` (`user_id`, `updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='会话元信息表';

-- ----------------------------------------------------------------------------
-- 人工审批（HITL）任务表
--
-- 只记录"哪个会话的哪个工具调用被挂起了"，用于人工审批、以及恢复时按 id 精确配对。
-- 恢复链路依赖 tool_use_id：挂起时记录 ToolUseBlock.id -> 审批后构造同 id 的
-- ToolResultBlock 回填，框架靠这个 id 把两者配成一对，所以建唯一键防止重复挂起。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `agent_hitl_task`
(
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    task_id       VARCHAR(64)  NOT NULL COMMENT '对外暴露的审批任务号（UUID）',
    user_id       VARCHAR(64)  NOT NULL COMMENT '用户标识',
    session_id    VARCHAR(255) NOT NULL COMMENT '会话 id（不含 userId，最终 key 为 userId:sessionId）',
    tool_use_id   VARCHAR(128) NOT NULL COMMENT '挂起的 ToolUseBlock id，恢复回填时必须精确匹配',
    tool_name     VARCHAR(128) NOT NULL COMMENT '被拦截的工具名：upload_document / upload2Rag',
    tool_input    TEXT         NULL COMMENT 'ToolUseBlock 的入参 JSON，审批通过后据此真正执行工具',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT '审批状态：PENDING / APPROVED / REJECTED',
    decision_note VARCHAR(500) NULL COMMENT '审批备注（如拒绝原因）',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '挂起时间',
    decided_at    DATETIME     NULL COMMENT '审批时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    UNIQUE KEY `uk_tool_use_id` (`tool_use_id`),
    KEY `idx_session` (`user_id`, `session_id`),
    KEY `idx_status_created` (`status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT ='人工审批（HITL）任务';

-- ----------------------------------------------------------------------------
-- 执行后自查
-- ----------------------------------------------------------------------------
-- SHOW TABLES;
-- SHOW CREATE TABLE agent_markdown_file;
-- SELECT id, phone, nickname, root_folder FROM user_info;
-- SELECT task_id, user_id, session_id, tool_name, status, created_at
--   FROM agent_hitl_task ORDER BY created_at DESC LIMIT 10;
--
-- 过期未审批任务清理（后续做 TTL 时用）：
--   DELETE FROM agent_hitl_task
--    WHERE status = 'PENDING' AND created_at < DATE_SUB(NOW(), INTERVAL 7 DAY);

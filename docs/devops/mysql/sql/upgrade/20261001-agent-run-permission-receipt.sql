CREATE TABLE IF NOT EXISTS `agent_run_permission_request` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `request_id` VARCHAR(64) NOT NULL COMMENT 'Hexgent PermissionRequest 稳定标识',
    `run_id` VARCHAR(64) NOT NULL COMMENT 'Workspace AgentRun 标识',
    `tool_name` VARCHAR(128) NOT NULL COMMENT '请求授权的工具名',
    `description` VARCHAR(1024) DEFAULT NULL COMMENT '面向用户的授权说明',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING,1=ALLOWED,2=DENIED',
    `response_id` VARCHAR(128) DEFAULT NULL COMMENT '首次人工决定对应的 Runtime responseId',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'WS 接收时间',
    `resolved_at` DATETIME DEFAULT NULL COMMENT '人工决定持久化时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_run_permission_request_id` (`request_id`),
    KEY `idx_agent_run_permission_run_status` (`run_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AgentRun Permission 请求收据与人工决定';

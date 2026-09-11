USE `hex_workspace`;

CREATE TABLE IF NOT EXISTS `document_upload_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_code` VARCHAR(64) NOT NULL COMMENT '上传任务编码',
    `document_id` BIGINT NOT NULL COMMENT '关联文档ID',
    `staging_path` VARCHAR(512) NOT NULL COMMENT 'MinIO临时对象路径',
    `target_path` VARCHAR(512) NOT NULL COMMENT 'MinIO正式对象路径',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING / 1=RUNNING / 2=SUCCEEDED / 3=RETRY_WAIT',
    `retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    `next_retry_at` DATETIME DEFAULT NULL COMMENT '下次重试时间',
    `failure_reason` VARCHAR(512) DEFAULT NULL COMMENT '最近失败原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_upload_task_code` (`task_code`),
    KEY `idx_document_upload_task_retry` (`status`, `next_retry_at`),
    KEY `idx_document_upload_task_document` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档上传异步任务表';

CREATE TABLE IF NOT EXISTS `document_upload_outbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_code` VARCHAR(64) NOT NULL COMMENT 'Outbox事件编码',
    `task_code` VARCHAR(64) NOT NULL COMMENT '关联上传任务编码',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING / 1=SENT',
    `failure_reason` VARCHAR(512) DEFAULT NULL COMMENT '最近投递失败原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_upload_outbox_event_code` (`event_code`),
    KEY `idx_document_upload_outbox_pending` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档上传事务发件箱表';

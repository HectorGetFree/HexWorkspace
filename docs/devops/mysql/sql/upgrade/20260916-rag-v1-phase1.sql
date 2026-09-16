-- Hex Workspace RAG V1 Phase 1 既有环境升级脚本。
-- 执行顺序：
-- 1. 先对 hex_workspace 做可恢复备份，并确认当前应用实例已停止写入 document。
-- 2. 执行本文件一次；完成后校验 information_schema 中字段、六张 knowledge_* 表与索引存在。
-- 3. 部署 Phase 1 应用。旧 /knowledge 接口默认关闭；仅在接受旧接口风险时显式设置环境变量开启。
-- 本脚本面向尚未执行过 Phase 1 的数据库；重复执行前必须先检查 schema，避免重复 ADD COLUMN。

ALTER TABLE `document`
    ADD COLUMN `index_input_revision` BIGINT NOT NULL DEFAULT 0
        COMMENT '索引输入修订号；内容版本生效或标题变更时递增'
        AFTER `current_version_no`;

CREATE TABLE IF NOT EXISTS `knowledge_embedding_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_hash` VARCHAR(64) NOT NULL COMMENT '不可变向量空间配置摘要',
    `provider` VARCHAR(64) NOT NULL COMMENT 'Embedding 提供方',
    `model` VARCHAR(128) NOT NULL COMMENT 'Embedding 模型',
    `dimension` INT NOT NULL COMMENT '向量维度',
    `distance` VARCHAR(32) NOT NULL COMMENT '距离度量',
    `collection_name` VARCHAR(128) NOT NULL COMMENT 'Qdrant Collection',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_embedding_config_hash` (`config_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识索引不可变Embedding配置';

CREATE TABLE IF NOT EXISTS `knowledge_index_job` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `job_code` VARCHAR(64) NOT NULL COMMENT '任务编码',
    `request_key` VARCHAR(160) NOT NULL COMMENT '自动任务稳定幂等键或手动重建唯一键',
    `workspace_id` BIGINT NOT NULL COMMENT '所属Workspace',
    `document_id` BIGINT NOT NULL COMMENT '所属文档',
    `version_no` BIGINT NOT NULL COMMENT '绑定不可变内容版本',
    `index_input_revision` BIGINT NOT NULL COMMENT '绑定索引输入修订',
    `job_type` TINYINT UNSIGNED NOT NULL COMMENT '0=AUTOMATIC / 1=MANUAL_REBUILD',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING / 1=RUNNING / 2=SUCCEEDED / 3=FAILED / 4=SKIPPED',
    `stage` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=QUEUED，后续阶段扩展解析与构建步骤',
    `worker_token` VARCHAR(64) DEFAULT NULL COMMENT '当前Worker执行令牌',
    `running_lease_until` DATETIME DEFAULT NULL COMMENT '执行租约截止时间',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT '已失败重试次数',
    `next_retry_at` DATETIME DEFAULT NULL COMMENT '下次允许重试时间',
    `failure_reason` VARCHAR(512) DEFAULT NULL COMMENT '最近失败原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_index_job_code` (`job_code`),
    UNIQUE KEY `uk_knowledge_index_job_request` (`request_key`),
    KEY `idx_knowledge_index_job_retry` (`status`, `next_retry_at`),
    KEY `idx_knowledge_index_job_lease` (`status`, `running_lease_until`),
    KEY `idx_knowledge_index_job_document` (`document_id`, `version_no`, `index_input_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识索引异步任务';

CREATE TABLE IF NOT EXISTS `knowledge_index_generation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `generation_id` VARCHAR(64) NOT NULL COMMENT '构建尝试唯一标识',
    `job_id` BIGINT NOT NULL COMMENT '来源任务',
    `workspace_id` BIGINT NOT NULL COMMENT '所属Workspace',
    `document_id` BIGINT NOT NULL COMMENT '所属文档',
    `version_no` BIGINT NOT NULL COMMENT '绑定版本',
    `index_input_revision` BIGINT NOT NULL COMMENT '绑定修订',
    `embedding_config_id` BIGINT DEFAULT NULL COMMENT 'Embedding配置快照',
    `chunking_config_hash` VARCHAR(64) DEFAULT NULL COMMENT 'Chunk策略摘要',
    `chunk_artifact_key` VARCHAR(512) DEFAULT NULL COMMENT 'Chunk产物路径',
    `chunk_artifact_checksum` VARCHAR(64) DEFAULT NULL COMMENT 'Chunk产物摘要',
    `chunk_count` INT DEFAULT NULL COMMENT 'Chunk数量',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=BUILDING / 1=READY / 2=FAILED',
    `worker_token` VARCHAR(64) DEFAULT NULL COMMENT '构建Worker令牌',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_index_generation_id` (`generation_id`),
    KEY `idx_knowledge_index_generation_document` (`document_id`, `version_no`, `index_input_revision`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识索引构建Generation';

CREATE TABLE IF NOT EXISTS `knowledge_index_state` (
    `document_id` BIGINT NOT NULL COMMENT '文档ID',
    `active_generation_id` VARCHAR(64) DEFAULT NULL COMMENT '当前已发布Generation',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档当前有效知识索引指针';

CREATE TABLE IF NOT EXISTS `knowledge_parse_artifact` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '文档ID',
    `version_no` BIGINT NOT NULL COMMENT '不可变版本号',
    `source_checksum` VARCHAR(64) NOT NULL COMMENT '源内容摘要',
    `parser_version` VARCHAR(64) NOT NULL COMMENT '解析器版本',
    `schema_version` VARCHAR(64) NOT NULL COMMENT '产物Schema版本',
    `artifact_key` VARCHAR(512) DEFAULT NULL COMMENT '解析产物路径',
    `artifact_checksum` VARCHAR(64) DEFAULT NULL COMMENT '产物摘要',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=BUILDING / 1=READY / 2=FAILED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_parse_artifact_version` (
        `document_id`, `version_no`, `source_checksum`, `parser_version`, `schema_version`
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识解析产物元数据';

CREATE TABLE IF NOT EXISTS `knowledge_index_outbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_code` VARCHAR(64) NOT NULL COMMENT '事件编码',
    `job_code` VARCHAR(64) NOT NULL COMMENT '关联知识索引任务编码',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING / 1=SENT / 2=PUBLISHING',
    `publishing_token` VARCHAR(64) DEFAULT NULL COMMENT '当前发布者令牌',
    `publishing_lease_until` DATETIME DEFAULT NULL COMMENT '发布租约截止时间',
    `failure_reason` VARCHAR(512) DEFAULT NULL COMMENT '最近发布失败原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_index_outbox_event_code` (`event_code`),
    UNIQUE KEY `uk_knowledge_index_outbox_job_code` (`job_code`),
    KEY `idx_knowledge_index_outbox_publish` (`status`, `publishing_lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识索引可靠消息事件表';

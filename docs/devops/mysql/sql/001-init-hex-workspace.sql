CREATE DATABASE IF NOT EXISTS `hex_workspace`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `hex_workspace`;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '密码哈希',
    `status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=ACTIVE / 1=DISABLED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

CREATE TABLE IF NOT EXISTS `workspace` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workspace_code` VARCHAR(64) NOT NULL COMMENT 'Workspace编码',
    `workspace_name` VARCHAR(128) NOT NULL COMMENT 'Workspace名称',
    `is_default` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为注册自动创建的默认资料库',
    `status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=ACTIVE / 1=ARCHIVED / 2=DELETED',
    `owner_user_id` BIGINT NOT NULL COMMENT 'Workspace拥有者用户ID',
    `description` VARCHAR(255) DEFAULT NULL COMMENT 'Workspace描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_code` (`workspace_code`),
    UNIQUE KEY `uk_workspace_owner_user_id` (`owner_user_id`),
    KEY `idx_workspace_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Workspace表';

CREATE TABLE IF NOT EXISTS `folder` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `folder_code` VARCHAR(64) NOT NULL COMMENT '文件夹编码',
    `workspace_id` BIGINT NOT NULL COMMENT '所属 Workspace ID',
    `parent_id` BIGINT DEFAULT NULL COMMENT '父文件夹ID，根目录为空',
    `name` VARCHAR(255) NOT NULL COMMENT '文件夹名称',
    `is_root` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为根目录',
    `root_workspace_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `is_root` = 1 THEN `workspace_id` ELSE NULL END) STORED COMMENT '根目录所属 Workspace；通过唯一索引确保每个 Workspace 最多一个根目录',
    `status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=ACTIVE / 1=DELETED',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '显式逻辑删除时间；未删除时为空',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_folder_code` (`folder_code`),
    UNIQUE KEY `uk_folder_root_workspace_id` (`root_workspace_id`),
    KEY `idx_folder_parent` (`workspace_id`, `parent_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Workspace目录树';

CREATE TABLE IF NOT EXISTS `document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `document_code` VARCHAR(64) NOT NULL COMMENT '文档唯一编码',
    `workspace_id` BIGINT NOT NULL COMMENT '所属 Workspace ID',
    `parent_id` BIGINT NOT NULL COMMENT '所属文件夹ID',
    `owner_user_id` BIGINT NOT NULL COMMENT '文档拥有者用户ID',
    `title` VARCHAR(255) NOT NULL COMMENT '文档标题',
    `file_name` VARCHAR(255) NOT NULL COMMENT '包含后缀的完整文件名',
    `document_type` TINYINT UNSIGNED NOT NULL COMMENT '0=LOCAL_UPLOADED / 1=PLATFORM_CREATED',
    `status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=CREATING / 1=WRITING / 2=ACTIVE / 3=DELETED / 4=FAILED',
    `current_version_no` BIGINT DEFAULT NULL COMMENT '当前正式内容版本号；ACTIVE 时必须存在',
    `index_input_revision` BIGINT NOT NULL DEFAULT 0 COMMENT '索引输入修订号；内容版本生效或标题变更时递增',
    `idempotency_key` VARCHAR(128) DEFAULT NULL COMMENT '本地上传客户端幂等键',
    `deleted_at` DATETIME DEFAULT NULL COMMENT '逻辑删除时间；未删除时为空',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_code` (`document_code`),
    UNIQUE KEY `uk_document_upload_idempotency` (`workspace_id`, `owner_user_id`, `idempotency_key`),
    KEY `idx_document_workspace_status_updated_at` (`workspace_id`, `status`, `updated_at`),
    KEY `idx_document_parent` (`workspace_id`, `parent_id`, `status`),
    KEY `idx_document_owner_user_id` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档聚合当前状态表';

CREATE TABLE IF NOT EXISTS `document_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '所属文档ID',
    `version_no` BIGINT NOT NULL COMMENT '文档内版本号，从1开始递增',
    `storage_type` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=MINIO',
    `bucket_name` VARCHAR(128) NOT NULL COMMENT '对象存储桶名称',
    `storage_path` VARCHAR(512) NOT NULL COMMENT '该版本对象存储路径',
    `content_type` VARCHAR(128) NOT NULL COMMENT 'MIME内容类型',
    `content_size` BIGINT NOT NULL DEFAULT 0 COMMENT '内容大小，单位字节',
    `content_hash` VARCHAR(128) NOT NULL COMMENT '内容SHA-256摘要',
    `created_by` BIGINT NOT NULL COMMENT '版本创建者用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_version` (`document_id`, `version_no`),
    KEY `idx_document_version_created_at` (`document_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档不可变内容版本表';

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

CREATE TABLE IF NOT EXISTS `knowledge_serving_vector_space` (
    `id` TINYINT UNSIGNED NOT NULL COMMENT '固定为1的单例主键',
    `embedding_config_id` BIGINT NOT NULL COMMENT '当前权威Embedding配置快照',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='当前部署生效的唯一向量空间';

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
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=BUILDING / 1=READY / 2=FAILED / 3=CLEANING / 4=CLEANED',
    `worker_token` VARCHAR(64) DEFAULT NULL COMMENT '构建Worker令牌',
    `cleanup_token` VARCHAR(64) DEFAULT NULL COMMENT '清理Worker领取令牌',
    `cleanup_lease_until` DATETIME DEFAULT NULL COMMENT '清理领取租约截止时间',
    `cleaned_at` DATETIME DEFAULT NULL COMMENT '清理完成时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_knowledge_index_generation_id` (`generation_id`),
    KEY `idx_knowledge_index_generation_document` (`document_id`, `version_no`, `index_input_revision`),
    KEY `idx_knowledge_index_generation_cleanup` (`status`, `updated_at`, `cleanup_lease_until`)
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

CREATE TABLE IF NOT EXISTS `document_upload_task` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `task_code` VARCHAR(64) NOT NULL COMMENT '上传任务编码',
    `document_id` BIGINT NOT NULL COMMENT '关联文档ID',
    `version_no` BIGINT NOT NULL COMMENT '本任务写入的目标版本号',
    `created_by` BIGINT NOT NULL COMMENT '本次内容版本创建者用户ID',
    `staging_path` VARCHAR(512) DEFAULT NULL COMMENT 'MinIO临时对象路径；终态任务清理后为空',
    `target_path` VARCHAR(512) NOT NULL COMMENT 'MinIO正式对象路径',
    `content_type` VARCHAR(128) NOT NULL COMMENT '正式对象MIME类型',
    `bucket_name` VARCHAR(128) NOT NULL COMMENT '正式对象Bucket名称',
    `content_size` BIGINT NOT NULL COMMENT '正式对象内容大小，单位字节',
    `content_hash` VARCHAR(128) NOT NULL COMMENT '正式对象内容SHA-256摘要',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING / 1=RUNNING / 2=SUCCEEDED / 3=RETRY_WAIT / 4=FAILED',
    `execution_token` VARCHAR(64) DEFAULT NULL COMMENT '当前消费者执行令牌',
    `running_lease_until` DATETIME DEFAULT NULL COMMENT '当前消费者执行租约截止时间',
    `retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '失败重试次数',
    `next_retry_at` DATETIME DEFAULT NULL COMMENT '下次重试时间',
    `failure_reason` VARCHAR(512) DEFAULT NULL COMMENT '最近失败原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_upload_task_code` (`task_code`),
    UNIQUE KEY `uk_document_upload_task_document_version` (`document_id`, `version_no`),
    KEY `idx_document_upload_task_retry` (`status`, `next_retry_at`),
    KEY `idx_document_upload_task_running_lease` (`status`, `running_lease_until`),
    KEY `idx_document_upload_task_document` (`document_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档上传异步任务表';

CREATE TABLE IF NOT EXISTS `document_upload_outbox_event` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `event_code` VARCHAR(64) NOT NULL COMMENT 'Outbox事件编码',
    `task_code` VARCHAR(64) NOT NULL COMMENT '关联上传任务编码',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING / 1=SENT / 2=PUBLISHING',
    `publishing_token` VARCHAR(64) DEFAULT NULL COMMENT '当前发布器实例持有的发布令牌',
    `publishing_lease_until` DATETIME DEFAULT NULL COMMENT '当前发布令牌的有效截止时间',
    `failure_reason` VARCHAR(512) DEFAULT NULL COMMENT '最近投递失败原因',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_upload_outbox_event_code` (`event_code`),
    KEY `idx_document_upload_outbox_pending` (`status`, `id`),
    KEY `idx_document_upload_outbox_publishing_lease` (`status`, `publishing_lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档上传事务发件箱表';

CREATE TABLE IF NOT EXISTS `workspace_conversation` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `conversation_id` VARCHAR(64) NOT NULL COMMENT 'Workspace Conversation 稳定标识',
    `workspace_id` BIGINT NOT NULL COMMENT '所属 Workspace',
    `user_id` BIGINT NOT NULL COMMENT '发起用户',
    `title` VARCHAR(255) DEFAULT NULL COMMENT '会话标题',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_conversation_id` (`conversation_id`),
    KEY `idx_workspace_conversation_owner` (`workspace_id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Workspace Agent Conversation';

CREATE TABLE IF NOT EXISTS `agent_run` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `run_id` VARCHAR(64) NOT NULL COMMENT 'WS AgentRun 稳定业务标识',
    `conversation_id` VARCHAR(64) NOT NULL COMMENT '所属 Conversation',
    `workspace_id` BIGINT NOT NULL COMMENT '所属 Workspace',
    `initiator_user_id` BIGINT NOT NULL COMMENT '发起用户',
    `status` TINYINT UNSIGNED NOT NULL COMMENT '0=PENDING,1=RUNNING,2=PAUSED,3=INCOMPLETE,4=SUCCEEDED,5=FAILED,6=CANCELLED',
    `instruction` TEXT NOT NULL COMMENT 'StartRun 原始指令',
    `resource_scope` JSON DEFAULT NULL COMMENT '创建时最大资源边界',
    `allowed_tools` JSON DEFAULT NULL COMMENT '创建时最大工具边界',
    `version` BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发版本',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `started_at` DATETIME DEFAULT NULL COMMENT '开始时间',
    `finished_at` DATETIME DEFAULT NULL COMMENT '终止时间',
    `failure_code` VARCHAR(64) DEFAULT NULL COMMENT '稳定失败码',
    `failure_message` VARCHAR(512) DEFAULT NULL COMMENT '失败信息',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_run_id` (`run_id`),
    KEY `idx_agent_run_conversation_status` (`conversation_id`, `status`),
    KEY `idx_agent_run_workspace_user` (`workspace_id`, `initiator_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Workspace AgentRun';

-- 业务约束：
-- 1. 不使用数据库外键，交由应用层保证 user_id / workspace_id / owner_user_id 的引用一致性。
-- 2. Workspace 只表达资料归属；访问能力由资源级权限承载，不存在 Workspace 成员角色。

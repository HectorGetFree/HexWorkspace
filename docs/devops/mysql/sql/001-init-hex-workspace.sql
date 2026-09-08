CREATE DATABASE IF NOT EXISTS `hex_workspace`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `hex_workspace`;

CREATE TABLE IF NOT EXISTS `user` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username` VARCHAR(64) NOT NULL COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT '密码哈希',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / LOCKED / DISABLED',
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
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED / DELETED',
    `owner_user_id` BIGINT NOT NULL COMMENT 'Workspace拥有者用户ID',
    `description` VARCHAR(255) DEFAULT NULL COMMENT 'Workspace描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_code` (`workspace_code`),
    KEY `idx_workspace_owner_default` (`owner_user_id`, `is_default`),
    KEY `idx_workspace_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Workspace表';

CREATE TABLE IF NOT EXISTS `file` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_code` VARCHAR(64) NOT NULL COMMENT '文件编码',
    `workspace_id` BIGINT NOT NULL COMMENT '所属 Workspace ID',
    `creator_user_id` BIGINT NOT NULL COMMENT '创建者用户ID',
    `file_purpose` VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL' COMMENT 'ORIGINAL 用户上传原始文件 / DERIVED 系统派生文件',
    `file_name` VARCHAR(255) NOT NULL COMMENT '文件展示名称',
    `origin_name` VARCHAR(255) NOT NULL COMMENT '原始文件名',
    `file_suffix` VARCHAR(32) DEFAULT NULL COMMENT '文件后缀',
    `content_type` VARCHAR(128) DEFAULT NULL COMMENT '文件内容类型',
    `file_size` BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
    `storage_type` VARCHAR(32) NOT NULL COMMENT '存储类型，例如 LOCAL',
    `bucket_name` VARCHAR(128) NOT NULL COMMENT '对象存储桶名称',
    `storage_path` VARCHAR(512) NOT NULL COMMENT '文件存储路径',
    `file_hash` VARCHAR(128) DEFAULT NULL COMMENT '文件摘要值',
    `status` VARCHAR(16) NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED / SUCCESS / FAILED / DELETED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_file_code` (`file_code`),
    KEY `idx_file_workspace_id` (`workspace_id`),
    KEY `idx_file_bucket_name` (`bucket_name`),
    KEY `idx_file_creator_user_id` (`creator_user_id`),
    KEY `idx_file_workspace_purpose` (`workspace_id`, `file_purpose`),
    KEY `idx_file_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件表';

CREATE TABLE IF NOT EXISTS `document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `document_code` VARCHAR(64) NOT NULL COMMENT '文档唯一编码',
    `workspace_id` BIGINT NOT NULL COMMENT '所属 Workspace ID',
    `owner_user_id` BIGINT NOT NULL COMMENT '文档拥有者用户ID',
    `source_file_id` BIGINT NOT NULL COMMENT '初始上传文件ID',
    `title` VARCHAR(255) NOT NULL COMMENT '文档标题',
    `document_type` VARCHAR(32) NOT NULL COMMENT 'UPLOADED / RICH_TEXT',
    `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED / DELETED',
    `current_version` INT NOT NULL DEFAULT 1 COMMENT '当前版本号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_code` (`document_code`),
    KEY `idx_document_workspace_status_updated_at` (`workspace_id`, `status`, `updated_at`),
    KEY `idx_document_owner_user_id` (`owner_user_id`),
    KEY `idx_document_source_file_id` (`source_file_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档聚合当前状态表';

CREATE TABLE IF NOT EXISTS `document_version` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `document_id` BIGINT NOT NULL COMMENT '所属文档ID',
    `version_number` INT NOT NULL COMMENT '版本号',
    `file_id` BIGINT NOT NULL COMMENT '版本文件快照ID',
    `content_hash` VARCHAR(128) DEFAULT NULL COMMENT '版本内容摘要，用于幂等与索引去重',
    `extracted_file_id` BIGINT DEFAULT NULL COMMENT '解析文本产物文件ID',
    `parse_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSING / SUCCESS / FAILED',
    `parser_version` VARCHAR(64) DEFAULT NULL COMMENT '解析器版本',
    `parsed_at` DATETIME DEFAULT NULL COMMENT '解析完成时间',
    `change_summary` VARCHAR(500) DEFAULT NULL COMMENT '变更说明',
    `created_by_user_id` BIGINT NOT NULL COMMENT '创建版本的用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_version` (`document_id`, `version_number`),
    KEY `idx_document_version_file_id` (`file_id`),
    KEY `idx_document_version_extracted_file_id` (`extracted_file_id`),
    KEY `idx_document_version_parse_status` (`parse_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档不可变版本快照表';

-- 业务约束：
-- 1. 不使用数据库外键，交由应用层保证 user_id / workspace_id / owner_user_id 的引用一致性。
-- 2. Workspace 只表达资料归属；访问能力由资源级权限承载，不存在 Workspace 成员角色。

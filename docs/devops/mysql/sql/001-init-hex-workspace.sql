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
    `status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=ACTIVE / 1=DELETED',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_folder_code` (`folder_code`),
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
    `status` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0=CREATING / 1=WRITING / 2=ACTIVE / 3=DELETED',
    `storage_type` TINYINT UNSIGNED NOT NULL COMMENT '0=MINIO',
    `bucket_name` VARCHAR(128) NOT NULL COMMENT '对象存储桶名称',
    `storage_path` VARCHAR(512) NOT NULL COMMENT '对象存储路径',
    `content_type` VARCHAR(128) NOT NULL COMMENT 'MIME 内容类型',
    `content_size` BIGINT NOT NULL DEFAULT 0 COMMENT '内容大小，单位字节',
    `content_hash` VARCHAR(128) NOT NULL COMMENT '内容 SHA-256 摘要',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_document_code` (`document_code`),
    KEY `idx_document_workspace_status_updated_at` (`workspace_id`, `status`, `updated_at`),
    KEY `idx_document_parent` (`workspace_id`, `parent_id`, `status`),
    KEY `idx_document_owner_user_id` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档聚合当前状态表';

-- 业务约束：
-- 1. 不使用数据库外键，交由应用层保证 user_id / workspace_id / owner_user_id 的引用一致性。
-- 2. Workspace 只表达资料归属；访问能力由资源级权限承载，不存在 Workspace 成员角色。

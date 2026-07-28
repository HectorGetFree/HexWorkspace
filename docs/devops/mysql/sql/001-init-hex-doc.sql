CREATE DATABASE IF NOT EXISTS `hex_doc`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `hex_doc`;

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

CREATE TABLE IF NOT EXISTS `tenant` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `tenant_code` VARCHAR(64) NOT NULL COMMENT '租户编码',
    `tenant_name` VARCHAR(128) NOT NULL COMMENT '租户名称',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / SUSPENDED',
    `owner_user_id` BIGINT NOT NULL COMMENT '租户拥有者ID',
    `description` VARCHAR(255) DEFAULT NULL COMMENT '租户描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_code`),
    KEY `idx_tenant_owner_user_id` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户表';

CREATE TABLE IF NOT EXISTS `membership` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id` BIGINT NOT NULL COMMENT '用户ID',
    `tenant_id` BIGINT NOT NULL COMMENT '租户ID',
    `role` VARCHAR(16) NOT NULL COMMENT 'ADMIN / MEMBER',
    `status` VARCHAR(16) NOT NULL COMMENT 'INVITED / ACTIVE / SUSPENDED / LEFT / REMOVED',
    `joined_at` DATETIME DEFAULT NULL COMMENT '正式加入时间',
    `invited_at` DATETIME DEFAULT NULL COMMENT '发出邀请时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_membership_user_tenant` (`user_id`, `tenant_id`),
    KEY `idx_membership_user_id` (`user_id`),
    KEY `idx_membership_tenant_id` (`tenant_id`),
    KEY `idx_membership_tenant_status` (`tenant_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='租户成员关系表';

CREATE TABLE IF NOT EXISTS `file` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `file_code` VARCHAR(64) NOT NULL COMMENT '文件编码',
    `creator_user_id` BIGINT NOT NULL COMMENT '创建者用户ID',
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
    KEY `idx_file_bucket_name` (`bucket_name`),
    KEY `idx_file_creator_user_id` (`creator_user_id`),
    KEY `idx_file_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文件表';

-- 业务约束：
-- 1. 不使用数据库外键，交由应用层保证 user_id / tenant_id / owner_user_id 的引用一致性。
-- 2. 每个租户必须至少保留一个 ACTIVE 状态的 ADMIN，该约束需在成员退出、移除、冻结或降权时由应用服务校验。

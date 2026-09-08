USE `hex_workspace`;

-- 历史升级脚本：仅适用于 004 之前的旧版本；新安装请直接执行 001。
CREATE TABLE IF NOT EXISTS `workspace` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    `workspace_code` VARCHAR(64) NOT NULL COMMENT 'Workspace编码',
    `workspace_name` VARCHAR(128) NOT NULL COMMENT 'Workspace名称',
    `workspace_type` VARCHAR(16) NOT NULL COMMENT 'PERSONAL / TEAM',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED / DELETED',
    `owner_user_id` BIGINT NOT NULL COMMENT 'Workspace拥有者用户ID',
    `description` VARCHAR(255) DEFAULT NULL COMMENT 'Workspace描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workspace_code` (`workspace_code`),
    KEY `idx_workspace_owner_type` (`owner_user_id`, `workspace_type`),
    KEY `idx_workspace_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Workspace表';

-- 为升级前已存在的用户补齐个人资料库；重复执行不会覆盖已有 PERSONAL Workspace。
INSERT INTO `workspace` (
    workspace_code,
    workspace_name,
    workspace_type,
    status,
    owner_user_id,
    created_at,
    updated_at
)
SELECT
    CONCAT('ws_personal_', u.id),
    CONCAT(u.username, ' 的个人资料库'),
    'PERSONAL',
    'ACTIVE',
    u.id,
    NOW(),
    NOW()
FROM `user` u
LEFT JOIN `workspace` w
    ON w.owner_user_id = u.id
    AND w.workspace_type = 'PERSONAL'
WHERE w.id IS NULL;

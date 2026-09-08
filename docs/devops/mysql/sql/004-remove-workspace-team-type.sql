USE `hex_workspace`;

-- Workspace 不再承担团队成员制；此前 TEAM 数据保留为其 Owner 的普通资料容器。
ALTER TABLE `workspace`
    ADD COLUMN `is_default` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否为注册自动创建的默认资料库' AFTER `workspace_name`;

UPDATE `workspace`
SET `is_default` = CASE WHEN `workspace_type` = 'PERSONAL' THEN 1 ELSE 0 END;

ALTER TABLE `workspace`
    DROP INDEX `idx_workspace_owner_type`,
    DROP COLUMN `workspace_type`,
    ADD KEY `idx_workspace_owner_default` (`owner_user_id`, `is_default`);

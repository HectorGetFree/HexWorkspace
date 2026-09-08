USE `hex_workspace`;

-- 本脚本只执行一次，用于把 004 之前的运行数据切换到 Workspace 归属模型。
-- 旧 membership 不会被转换为权限：它只表示“加入过租户”，无法证明其获得某个文档/文件夹的访问授权。

CREATE TABLE `workspace_migration_map` (
    `legacy_tenant_id` BIGINT NOT NULL,
    `workspace_id` BIGINT NOT NULL,
    PRIMARY KEY (`legacy_tenant_id`),
    UNIQUE KEY `uk_workspace_migration_workspace_id` (`workspace_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='tenant 到 workspace 的一次性迁移映射';

INSERT INTO `workspace` (
    workspace_code, workspace_name, is_default, status, owner_user_id, description, created_at, updated_at
)
SELECT
    CONCAT('ws_migrated_', t.id), t.tenant_name, 0, t.status, t.owner_user_id, t.description, t.created_at, t.updated_at
FROM `tenant` t;

INSERT INTO `workspace_migration_map` (legacy_tenant_id, workspace_id)
SELECT t.id, w.id
FROM `tenant` t
JOIN `workspace` w ON w.workspace_code = CONCAT('ws_migrated_', t.id);

ALTER TABLE `file`
    ADD COLUMN `workspace_id` BIGINT NULL COMMENT '所属 Workspace ID' AFTER `file_code`;
UPDATE `file` f
JOIN `workspace_migration_map` m ON m.legacy_tenant_id = f.tenant_id
SET f.workspace_id = m.workspace_id;
ALTER TABLE `file`
    MODIFY COLUMN `workspace_id` BIGINT NOT NULL,
    DROP INDEX `idx_file_tenant_id`,
    DROP INDEX `idx_file_tenant_purpose`,
    DROP COLUMN `tenant_id`,
    ADD KEY `idx_file_workspace_id` (`workspace_id`),
    ADD KEY `idx_file_workspace_purpose` (`workspace_id`, `file_purpose`);

ALTER TABLE `document`
    ADD COLUMN `workspace_id` BIGINT NULL COMMENT '所属 Workspace ID' AFTER `document_code`;
UPDATE `document` d
JOIN `workspace_migration_map` m ON m.legacy_tenant_id = d.tenant_id
SET d.workspace_id = m.workspace_id;
ALTER TABLE `document`
    MODIFY COLUMN `workspace_id` BIGINT NOT NULL,
    DROP INDEX `idx_document_tenant_status_updated_at`,
    DROP COLUMN `tenant_id`,
    ADD KEY `idx_document_workspace_status_updated_at` (`workspace_id`, `status`, `updated_at`);

-- 旧成员记录保留为只读审计备份，运行系统不再读取该表；访问须从资源级授权重新审批。
RENAME TABLE `membership` TO `legacy_membership_backup_20260908`;
RENAME TABLE `tenant` TO `legacy_tenant_backup_20260908`;
DROP TABLE `workspace_migration_map`;

-- Qdrant 历史 payload 使用 tenant_id，无法由 MySQL DDL 原地修正；执行本脚本后必须对现存文档全量重建向量索引。

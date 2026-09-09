USE `hex_workspace`;

-- 执行前必须先清理同一 owner_user_id 下的多个 Workspace；唯一索引负责阻止后续再次创建。
ALTER TABLE `workspace`
    DROP INDEX `idx_workspace_owner_default`,
    ADD UNIQUE KEY `uk_workspace_owner_user_id` (`owner_user_id`);

CREATE TABLE `folder` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `folder_code` VARCHAR(64) NOT NULL,
    `workspace_id` BIGINT NOT NULL,
    `parent_id` BIGINT DEFAULT NULL,
    `name` VARCHAR(255) NOT NULL,
    `is_root` TINYINT(1) NOT NULL DEFAULT 0,
    `root_workspace_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `is_root` = 1 THEN `workspace_id` ELSE NULL END) STORED,
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_folder_code` (`folder_code`),
    UNIQUE KEY `uk_folder_root_workspace` (`root_workspace_id`),
    KEY `idx_folder_parent` (`workspace_id`, `parent_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO `folder` (folder_code, workspace_id, parent_id, name, is_root, status)
SELECT CONCAT('fld_root_', id), id, NULL, '根目录', 1, 'ACTIVE' FROM `workspace`;

ALTER TABLE `document`
    ADD COLUMN `parent_id` BIGINT NULL AFTER `workspace_id`,
    ADD COLUMN `file_name` VARCHAR(255) NULL AFTER `title`;

UPDATE `document` d
JOIN `folder` f ON f.workspace_id=d.workspace_id AND f.is_root=1
LEFT JOIN `file` source_file ON source_file.id=d.source_file_id
SET d.parent_id=f.id,
    d.file_name=COALESCE(NULLIF(source_file.origin_name, ''),
        CASE WHEN d.title LIKE '%.%' THEN d.title ELSE CONCAT(d.title, '.md') END),
    d.document_type=CASE WHEN d.document_type='UPLOADED' THEN 'LOCAL_UPLOADED' ELSE 'PLATFORM_CREATED' END;

ALTER TABLE `document`
    MODIFY COLUMN `parent_id` BIGINT NOT NULL,
    MODIFY COLUMN `file_name` VARCHAR(255) NOT NULL,
    ADD KEY `idx_document_parent` (`workspace_id`, `parent_id`, `status`);

-- 历史版本保留原解析状态；新建和上传的云文档默认由应用写入 NOT_REQUESTED。

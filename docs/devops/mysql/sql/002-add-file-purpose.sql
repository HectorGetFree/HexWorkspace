USE `hex_workspace`;

-- 已初始化的本地 MySQL 不会再次执行 001；升级环境需执行本脚本补充文件用途字段。
ALTER TABLE `file`
    ADD COLUMN `file_purpose` VARCHAR(16) NOT NULL DEFAULT 'ORIGINAL' COMMENT 'ORIGINAL 用户上传原始文件 / DERIVED 系统派生文件' AFTER `creator_user_id`,
    ADD KEY `idx_file_tenant_purpose` (`tenant_id`, `file_purpose`);

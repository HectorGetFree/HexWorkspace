ALTER TABLE `knowledge_index_generation`
    ADD COLUMN `cleanup_token` VARCHAR(64) DEFAULT NULL COMMENT '清理Worker领取令牌' AFTER `worker_token`,
    ADD COLUMN `cleanup_lease_until` DATETIME DEFAULT NULL COMMENT '清理领取租约截止时间' AFTER `cleanup_token`,
    ADD COLUMN `cleaned_at` DATETIME DEFAULT NULL COMMENT '清理完成时间' AFTER `cleanup_lease_until`;

ALTER TABLE `knowledge_index_generation`
    ADD KEY `idx_knowledge_index_generation_cleanup` (`status`, `updated_at`, `cleanup_lease_until`);

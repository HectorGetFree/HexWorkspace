USE `hex_workspace`;

CREATE TABLE IF NOT EXISTS `capability_mutation_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `run_id` VARCHAR(128) NOT NULL,
    `call_id` VARCHAR(128) NOT NULL,
    `capability` VARCHAR(128) NOT NULL,
    `request_fingerprint` CHAR(64) NOT NULL,
    `status` TINYINT UNSIGNED NOT NULL,
    `execution_token` VARCHAR(64) DEFAULT NULL,
    `lease_until` DATETIME DEFAULT NULL,
    `document_id` BIGINT DEFAULT NULL,
    `previous_version_id` BIGINT DEFAULT NULL,
    `version_id` BIGINT DEFAULT NULL,
    `target_version_no` BIGINT DEFAULT NULL,
    `error_code` VARCHAR(64) DEFAULT NULL,
    `error_message` VARCHAR(512) DEFAULT NULL,
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_capability_mutation_identity` (`run_id`, `call_id`),
    KEY `idx_capability_mutation_document` (`document_id`),
    KEY `idx_capability_mutation_status` (`status`, `updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

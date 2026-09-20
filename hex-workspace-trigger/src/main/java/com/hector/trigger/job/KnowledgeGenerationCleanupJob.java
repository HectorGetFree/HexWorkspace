package com.hector.trigger.job;

import com.hector.domain.knowledge.service.IKnowledgeGenerationCleanupService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 定时清理超过保留期且已不再作为权威索引的 Generation。 */
@Component
public class KnowledgeGenerationCleanupJob {
    @Resource
    private IKnowledgeGenerationCleanupService cleanupService;

    @Value("${hexworkspace.knowledge.generation-cleanup.retention-hours:168}")
    private long retentionHours;

    @Value("${hexworkspace.knowledge.generation-cleanup.batch-size:20}")
    private int batchSize;

    @Value("${hexworkspace.knowledge.generation-cleanup.lease-minutes:10}")
    private int leaseMinutes;

    @Scheduled(fixedDelayString = "${hexworkspace.knowledge.generation-cleanup.fixed-delay-ms:3600000}")
    public void cleanExpiredGenerations() {
        cleanupService.cleanExpired(LocalDateTime.now().minusHours(retentionHours), batchSize, leaseMinutes);
    }
}

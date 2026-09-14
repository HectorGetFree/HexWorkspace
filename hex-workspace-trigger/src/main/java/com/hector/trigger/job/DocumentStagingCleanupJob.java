package com.hector.trigger.job;

import com.hector.domain.document.service.IDocumentStagingCleanupService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 定时清理未被上传任务引用的过期 staging 对象。 */
@Component
public class DocumentStagingCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(DocumentStagingCleanupJob.class);

    @Value("${hexworkspace.document-upload.staging-orphan-retention-hours}")
    private long stagingOrphanRetentionHours;

    @Resource
    private IDocumentStagingCleanupService stagingCleanupService;

    @Scheduled(fixedDelayString = "${hexworkspace.document-upload.staging-cleanup-fixed-delay-ms}")
    public void cleanExpiredOrphans() {
        LocalDateTime expiredBefore = LocalDateTime.now().minusHours(stagingOrphanRetentionHours);
        try {
            int deletedCount = stagingCleanupService.cleanExpiredOrphans(expiredBefore);
            log.info("文档staging定时清理完成 expiredBefore:{} deletedCount:{}", expiredBefore, deletedCount);
        } catch (Exception exception) {
            log.error("文档staging定时清理异常 expiredBefore:{}", expiredBefore, exception);
        }
    }
}

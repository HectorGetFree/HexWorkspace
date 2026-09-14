package com.hector.trigger.job;

import com.hector.domain.document.service.IDocumentStagingCleanupService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 定时清理孤儿对象及终态上传任务遗留的 staging 对象。 */
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
            // 1. 先清理无任务引用的崩溃孤儿对象。
            int orphanDeletedCount = stagingCleanupService.cleanExpiredOrphans(expiredBefore);
            // 2. 再清理终态任务的残留对象，并移除其任务路径引用。
            int terminalTaskDeletedCount = stagingCleanupService.cleanExpiredTerminalTaskObjects(expiredBefore);
            log.info("文档staging定时清理完成 expiredBefore:{} orphanDeletedCount:{} terminalTaskDeletedCount:{}",
                    expiredBefore, orphanDeletedCount, terminalTaskDeletedCount);
        } catch (Exception exception) {
            log.error("文档staging定时清理异常 expiredBefore:{}", expiredBefore, exception);
        }
    }
}

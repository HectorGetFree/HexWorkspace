package com.hector.infrastructure.rag;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.service.IKnowledgeIndexWorkerService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Phase 3 索引 Worker：只在持有 Job 租约时编排对象存储、模型和向量库外部调用。 */
@Component
public class KnowledgeIndexWorkerService implements IKnowledgeIndexWorkerService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexWorkerService.class);

    @Value("${hexworkspace.knowledge.worker.lease-seconds:120}")
    private int leaseSeconds;

    @Value("${hexworkspace.knowledge.worker.max-retry-count:3}")
    private int maxRetryCount;

    @Value("${hexworkspace.knowledge.worker.retry-delay-seconds:60}")
    private int retryDelaySeconds;

    @Resource
    private IKnowledgeIndexJobRepository jobRepository;

    @Resource
    private KnowledgeArtifactPreparationService artifactPreparationService;

    @Resource
    private GenerationIndexBuildService generationIndexBuildService;

    @Resource
    private GenerationPublicationService generationPublicationService;

    /** 消费重复消息是允许的；CAS 抢占失败时由其他 Worker 继续处理。 */
    @Override
    public void execute(String jobCode) {
        log.info("知识索引任务-执行开始 jobCode:{}", jobCode);
        String workerToken = UUID.randomUUID().toString();
        if (!jobRepository.claimForRunning(jobCode, workerToken, LocalDateTime.now().plusSeconds(leaseSeconds))) {
            log.info("知识索引任务-执行跳过，任务已被其他 Worker 抢占 jobCode:{}", jobCode);
            return;
        }
        String generationId = null;
        try {
            // 1. 解析与切块不在 MySQL 长事务内执行，完成后先续期再进入模型调用。
            generationId = artifactPreparationService.prepare(jobCode, workerToken);
            if (generationId == null) return;
            renewOrThrow(jobCode, workerToken);

            // 2. 向量构建涉及 Embedding 和 Qdrant；租约丢失时禁止继续发布。
            generationIndexBuildService.build(generationId, workerToken);
            renewOrThrow(jobCode, workerToken);

            // 3. 仅发布阶段开启短 MySQL 事务，原子切换权威 Generation 与 Job 成功状态。
            generationPublicationService.publish(generationId, jobCode, workerToken);
            log.info("知识索引任务-执行完成 jobCode:{} generationId:{}", jobCode, generationId);
        } catch (Exception exception) {
            if (generationId != null) {
                // 失败尝试不可能成为可读索引；残留向量按 Generation 隔离，后续可异步清理。
                generationIndexBuildService.markFailed(generationId, workerToken);
            }
            String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            boolean handled = jobRepository.finishFailureIfOwned(
                    jobCode,
                    workerToken,
                    maxRetryCount,
                    LocalDateTime.now().plusSeconds(retryDelaySeconds),
                    reason);
            if (!handled) {
                log.warn("知识索引任务-执行失败但执行权已失效 jobCode:{}", jobCode, exception);
                return;
            }
            log.error("知识索引任务-执行失败，已进入重试或终态 jobCode:{}", jobCode, exception);
        }
    }

    /** 由调度器回收进程异常退出留下的执行租约。 */
    @Override
    public void recoverExpiredRunningJobs() {
        LocalDateTime now = LocalDateTime.now();
        for (KnowledgeIndexJobEntity job : jobRepository.listExpiredRunning(now, 100)) {
            jobRepository.recoverExpiredRunning(job.getJobCode(), now, "知识索引 Worker 执行租约超时");
        }
    }

    @Override
    public List<KnowledgeIndexJobEntity> listPendingReadyJobs() {
        return jobRepository.listPendingReady(LocalDateTime.now(), 100);
    }

    private void renewOrThrow(String jobCode, String workerToken) {
        if (!jobRepository.renewLeaseIfOwned(jobCode, workerToken, LocalDateTime.now().plusSeconds(leaseSeconds))) {
            throw new IllegalStateException("知识索引 Worker 执行租约已失效");
        }
    }
}

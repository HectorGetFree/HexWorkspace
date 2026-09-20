package com.hector.infrastructure.rag;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexGenerationEntity;
import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;
import com.hector.domain.knowledge.repository.IKnowledgeIndexGenerationRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.domain.knowledge.repository.IServingVectorSpaceRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** 将已完整构建的 Generation 与 Job 成功状态在同一 MySQL 事务中发布。 */
@Component
public class GenerationPublicationService {
    private static final Logger log = LoggerFactory.getLogger(GenerationPublicationService.class);

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IKnowledgeIndexGenerationRepository generationRepository;

    @Resource
    private IKnowledgeIndexJobRepository jobRepository;

    @Resource
    private IKnowledgeIndexStateRepository indexStateRepository;

    @Resource
    private IServingVectorSpaceRepository servingVectorSpaceRepository;

    public void publish(String generationId, String jobCode, String workerToken) {
        log.info("Generation 发布开始 generationId:{} jobCode:{}", generationId, jobCode);
        Boolean published = transactionTemplate.execute(status -> {
            // 1. 按 Document → IndexState/Job 的顺序检查，避免与文档版本提交交叉发布旧输入。
            KnowledgeIndexGenerationEntity generation = generationRepository.findByGenerationId(generationId)
                    .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED));
            KnowledgeIndexJobEntity job = jobRepository.findByJobCode(jobCode)
                    .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED));
            DocumentEntity document = documentRepository.lockById(generation.getDocumentId())
                    .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
            Long servingConfigId = servingVectorSpaceRepository.findServingConfig()
                    .map(config -> config.getId())
                    .orElse(null);
            LocalDateTime now = LocalDateTime.now();
            if (generation.getStatus() != 1
                    || generation.getEmbeddingConfigId() == null
                    || !generation.getJobId().equals(job.getId())
                    || !workerToken.equals(generation.getWorkerToken()) || !workerToken.equals(job.getWorkerToken())
                    || job.getRunningLeaseUntil() == null || !job.getRunningLeaseUntil().isAfter(now)
                    || !generation.getWorkspaceId().equals(document.getWorkspaceId())
                    || !generation.getVersionNo().equals(document.getCurrentVersionNo())
                    || !generation.getIndexInputRevision().equals(document.getIndexInputRevision())
                    || !generation.getEmbeddingConfigId().equals(servingConfigId)) {
                status.setRollbackOnly();
                return false;
            }
            // 2. 先切换权威指针，再以条件更新结束 Job；任一步失败都会回滚指针。
            indexStateRepository.activate(document.getId(), generationId);
            boolean succeeded = jobRepository.markSucceededIfOwned(jobCode, workerToken, now);
            if (!succeeded) {
                // 3. Job 的条件更新失败代表租约已被回收；不得留下已切换的权威 Generation。
                status.setRollbackOnly();
            }
            return succeeded;
        });
        if (!Boolean.TRUE.equals(published)) {
            throw new BusinessException(ResponseCode.DOCUMENT_PARSE_FAILED);
        }
        log.info("Generation 发布完成 generationId:{} jobCode:{}", generationId, jobCode);
    }
}

package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexJobEntity;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexJobStatusVO;
import com.hector.domain.knowledge.model.valobj.KnowledgeIndexJobTypeVO;
import com.hector.domain.knowledge.repository.IKnowledgeIndexJobRepository;
import com.hector.infrastructure.dao.IKnowledgeIndexJobDao;
import com.hector.infrastructure.dao.po.KnowledgeIndexJob;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class KnowledgeIndexJobRepository implements IKnowledgeIndexJobRepository {
    @Resource
    private IKnowledgeIndexJobDao dao;

    @Override
    public boolean saveIfAbsent(KnowledgeIndexJobEntity entity) {
        return dao.insertIgnore(toKnowledgeIndexJobPo(entity)) == 1;
    }

    @Override
    public Optional<KnowledgeIndexJobEntity> findByRequestKey(String requestKey) {
        return Optional.ofNullable(dao.queryByRequestKey(requestKey))
                .map(this::toKnowledgeIndexJobEntity);
    }

    @Override
    public Optional<KnowledgeIndexJobEntity> findByJobCode(String jobCode) {
        return Optional.ofNullable(dao.queryByJobCode(jobCode))
                .map(this::toKnowledgeIndexJobEntity);
    }

    @Override
    public boolean claimForRunning(String jobCode, String workerToken, LocalDateTime runningLeaseUntil) {
        return dao.claimForRunning(jobCode, workerToken, runningLeaseUntil) == 1;
    }

    @Override
    public boolean recoverExpiredRunning(String jobCode, LocalDateTime now, String failureReason) {
        return dao.recoverExpiredRunning(jobCode, now, failureReason) == 1;
    }

    @Override
    public boolean markSkippedIfOwned(String jobCode, String workerToken, String reason) {
        return dao.markSkippedIfOwned(jobCode, workerToken, reason) == 1;
    }

    @Override
    public List<KnowledgeIndexJobEntity> listExpiredRunning(LocalDateTime now, int limit) {
        return dao.queryExpiredRunning(now, limit).stream()
                .map(this::toKnowledgeIndexJobEntity)
                .toList();
    }

    private KnowledgeIndexJob toKnowledgeIndexJobPo(KnowledgeIndexJobEntity entity) {
        return KnowledgeIndexJob.builder()
                .id(entity.getId())
                .jobCode(entity.getJobCode())
                .requestKey(entity.getRequestKey())
                .workspaceId(entity.getWorkspaceId())
                .documentId(entity.getDocumentId())
                .versionNo(entity.getVersionNo())
                .indexInputRevision(entity.getIndexInputRevision())
                .jobType(entity.getJobType().getCode())
                .status(entity.getStatus().getCode())
                .stage(entity.getStage())
                .workerToken(entity.getWorkerToken())
                .runningLeaseUntil(entity.getRunningLeaseUntil())
                .retryCount(entity.getRetryCount())
                .nextRetryAt(entity.getNextRetryAt())
                .failureReason(entity.getFailureReason())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private KnowledgeIndexJobEntity toKnowledgeIndexJobEntity(KnowledgeIndexJob job) {
        return KnowledgeIndexJobEntity.builder()
                .id(job.getId())
                .jobCode(job.getJobCode())
                .requestKey(job.getRequestKey())
                .workspaceId(job.getWorkspaceId())
                .documentId(job.getDocumentId())
                .versionNo(job.getVersionNo())
                .indexInputRevision(job.getIndexInputRevision())
                .jobType(KnowledgeIndexJobTypeVO.fromCode(job.getJobType()))
                .status(KnowledgeIndexJobStatusVO.fromCode(job.getStatus()))
                .stage(job.getStage())
                .workerToken(job.getWorkerToken())
                .runningLeaseUntil(job.getRunningLeaseUntil())
                .retryCount(job.getRetryCount())
                .nextRetryAt(job.getNextRetryAt())
                .failureReason(job.getFailureReason())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}

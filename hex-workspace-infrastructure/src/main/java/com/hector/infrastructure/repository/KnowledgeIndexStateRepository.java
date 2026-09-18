package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeIndexStateEntity;
import com.hector.domain.knowledge.repository.IKnowledgeIndexStateRepository;
import com.hector.infrastructure.dao.IKnowledgeIndexStateDao;
import com.hector.infrastructure.dao.po.KnowledgeIndexState;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 已发布 Generation 指针仓储实现。 */
@Repository
public class KnowledgeIndexStateRepository implements IKnowledgeIndexStateRepository {

    @Resource
    private IKnowledgeIndexStateDao knowledgeIndexStateDao;

    @Override
    public Optional<KnowledgeIndexStateEntity> findByDocumentId(Long documentId) {
        return Optional.ofNullable(knowledgeIndexStateDao.queryByDocumentId(documentId)).map(this::toKnowledgeIndexStateEntity);
    }

    @Override
    public void activate(Long documentId, String generationId) {
        knowledgeIndexStateDao.upsertActive(documentId, generationId);
    }

    private KnowledgeIndexStateEntity toKnowledgeIndexStateEntity(KnowledgeIndexState po) {
        return KnowledgeIndexStateEntity.builder().documentId(po.getDocumentId())
                .activeGenerationId(po.getActiveGenerationId()).updatedAt(po.getUpdatedAt()).build();
    }
}

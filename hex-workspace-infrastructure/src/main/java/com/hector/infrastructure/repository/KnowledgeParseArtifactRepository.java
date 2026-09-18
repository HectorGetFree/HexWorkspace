package com.hector.infrastructure.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeParseArtifactEntity;
import com.hector.domain.knowledge.repository.IKnowledgeParseArtifactRepository;
import com.hector.infrastructure.dao.IKnowledgeParseArtifactDao;
import com.hector.infrastructure.dao.po.KnowledgeParseArtifact;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 解析产物元数据仓储实现。 */
@Repository
public class KnowledgeParseArtifactRepository implements IKnowledgeParseArtifactRepository {

    @Resource
    private IKnowledgeParseArtifactDao knowledgeParseArtifactDao;

    @Override
    public Optional<KnowledgeParseArtifactEntity> findByIdentity(Long documentId, Long versionNo, String sourceChecksum,
                                                                  String parserVersion, String schemaVersion) {
        return Optional.ofNullable(knowledgeParseArtifactDao.queryByIdentity(documentId, versionNo, sourceChecksum,
                parserVersion, schemaVersion)).map(this::toEntity);
    }

    @Override
    public KnowledgeParseArtifactEntity saveIfAbsent(KnowledgeParseArtifactEntity artifact) {
        knowledgeParseArtifactDao.insertIgnore(toPo(artifact));
        return findByIdentity(artifact.getDocumentId(), artifact.getVersionNo(), artifact.getSourceChecksum(),
                artifact.getParserVersion(), artifact.getSchemaVersion()).orElseThrow();
    }

    private KnowledgeParseArtifact toPo(KnowledgeParseArtifactEntity entity) {
        return KnowledgeParseArtifact.builder()
                .id(entity.getId())
                .documentId(entity.getDocumentId())
                .versionNo(entity.getVersionNo())
                .sourceChecksum(entity.getSourceChecksum())
                .parserVersion(entity.getParserVersion())
                .schemaVersion(entity.getSchemaVersion())
                .artifactKey(entity.getArtifactKey())
                .artifactChecksum(entity.getArtifactChecksum())
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private KnowledgeParseArtifactEntity toEntity(KnowledgeParseArtifact po) {
        return KnowledgeParseArtifactEntity.builder()
                .id(po.getId())
                .documentId(po.getDocumentId())
                .versionNo(po.getVersionNo())
                .sourceChecksum(po.getSourceChecksum())
                .parserVersion(po.getParserVersion())
                .schemaVersion(po.getSchemaVersion())
                .artifactKey(po.getArtifactKey())
                .artifactChecksum(po.getArtifactChecksum())
                .status(po.getStatus())
                .createdAt(po.getCreatedAt())
                .updatedAt(po.getUpdatedAt())
                .build();
    }
}

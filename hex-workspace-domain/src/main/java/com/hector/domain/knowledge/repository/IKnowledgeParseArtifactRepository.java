package com.hector.domain.knowledge.repository;

import com.hector.domain.knowledge.model.entity.KnowledgeParseArtifactEntity;

import java.util.Optional;

/** 版本化解析产物元数据仓储。 */
public interface IKnowledgeParseArtifactRepository {

    Optional<KnowledgeParseArtifactEntity> findByIdentity(Long documentId, Long versionNo, String sourceChecksum,
                                                           String parserVersion, String schemaVersion);

    /** 查询指定文档版本唯一的 READY Parsed Artifact；多候选时拒绝猜测。 */
    Optional<KnowledgeParseArtifactEntity> findReadyByVersion(Long documentId, Long versionNo, String sourceChecksum);

    KnowledgeParseArtifactEntity saveIfAbsent(KnowledgeParseArtifactEntity artifact);
}

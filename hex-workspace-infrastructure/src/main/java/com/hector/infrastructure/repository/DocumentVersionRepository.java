package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentParseStatusVO;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.infrastructure.dao.IDocumentVersionDao;
import com.hector.infrastructure.dao.po.DocumentVersion;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文档版本仓储实现。
 */
@Repository
public class DocumentVersionRepository implements IDocumentVersionRepository {

    private static final Logger log = LoggerFactory.getLogger(DocumentVersionRepository.class);

    @Resource
    private IDocumentVersionDao documentVersionDao;

    @Override
    public DocumentVersionEntity save(DocumentVersionEntity documentVersionEntity) {
        log.info("文档版本仓储-保存版本开始 documentId:{} versionNumber:{} contentId:{}",
                documentVersionEntity.getDocumentId(), documentVersionEntity.getVersionNumber(),
                documentVersionEntity.getContentId());
        DocumentVersion documentVersion = toPo(documentVersionEntity);
        documentVersionDao.insert(documentVersion);
        DocumentVersionEntity savedDocumentVersionEntity = toEntity(documentVersion);
        log.info("文档版本仓储-保存版本完成 versionId:{} documentId:{} versionNumber:{}",
                savedDocumentVersionEntity.getId(), savedDocumentVersionEntity.getDocumentId(), savedDocumentVersionEntity.getVersionNumber());
        return savedDocumentVersionEntity;
    }

    @Override
    public void updateParseResult(DocumentVersionEntity documentVersionEntity) {
        log.info("文档版本仓储-更新解析结果开始 versionId:{} documentId:{} versionNumber:{} parseStatus:{}",
                documentVersionEntity.getId(), documentVersionEntity.getDocumentId(),
                documentVersionEntity.getVersionNumber(), documentVersionEntity.getParseStatus());
        documentVersionDao.updateParseResult(toPo(documentVersionEntity));
        log.info("文档版本仓储-更新解析结果完成 versionId:{} documentId:{} versionNumber:{}",
                documentVersionEntity.getId(), documentVersionEntity.getDocumentId(), documentVersionEntity.getVersionNumber());
    }

    @Override
    public Optional<DocumentVersionEntity> findByDocumentIdAndVersionNumber(Long documentId, Integer versionNumber) {
        log.info("文档版本仓储-按文档与版本号查询开始 documentId:{} versionNumber:{}", documentId, versionNumber);
        DocumentVersion documentVersion = documentVersionDao.queryByDocumentIdAndVersionNumber(documentId, versionNumber);
        log.info("文档版本仓储-按文档与版本号查询完成 documentId:{} versionNumber:{} found:{}",
                documentId, versionNumber, documentVersion != null);
        return documentVersion == null ? Optional.empty() : Optional.of(toEntity(documentVersion));
    }

    @Override
    public List<DocumentVersionEntity> listByDocumentId(Long documentId) {
        log.info("文档版本仓储-按文档查询列表开始 documentId:{}", documentId);
        List<DocumentVersionEntity> documentVersionEntities = documentVersionDao.queryByDocumentId(documentId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
        log.info("文档版本仓储-按文档查询列表完成 documentId:{} versionCount:{}",
                documentId, documentVersionEntities.size());
        return documentVersionEntities;
    }

    private DocumentVersion toPo(DocumentVersionEntity documentVersionEntity) {
        return DocumentVersion.builder()
                .id(documentVersionEntity.getId())
                .documentId(documentVersionEntity.getDocumentId())
                .versionNumber(documentVersionEntity.getVersionNumber())
                .contentId(documentVersionEntity.getContentId())
                .contentHash(documentVersionEntity.getContentHash())
                .extractedContentId(documentVersionEntity.getExtractedContentId())
                .parseStatus(documentVersionEntity.getParseStatus().name())
                .parserVersion(documentVersionEntity.getParserVersion())
                .parsedAt(documentVersionEntity.getParsedAt())
                .changeSummary(documentVersionEntity.getChangeSummary())
                .createdByUserId(documentVersionEntity.getCreatedByUserId())
                .createdAt(documentVersionEntity.getCreatedAt())
                .build();
    }

    private DocumentVersionEntity toEntity(DocumentVersion documentVersion) {
        return DocumentVersionEntity.builder()
                .id(documentVersion.getId())
                .documentId(documentVersion.getDocumentId())
                .versionNumber(documentVersion.getVersionNumber())
                .contentId(documentVersion.getContentId())
                .contentHash(documentVersion.getContentHash())
                .extractedContentId(documentVersion.getExtractedContentId())
                .parseStatus(DocumentParseStatusVO.valueOf(documentVersion.getParseStatus()))
                .parserVersion(documentVersion.getParserVersion())
                .parsedAt(documentVersion.getParsedAt())
                .changeSummary(documentVersion.getChangeSummary())
                .createdByUserId(documentVersion.getCreatedByUserId())
                .createdAt(documentVersion.getCreatedAt())
                .build();
    }
}

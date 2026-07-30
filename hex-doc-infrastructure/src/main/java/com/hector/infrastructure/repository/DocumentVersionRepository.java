package com.hector.infrastructure.repository;

import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentParseStatusVO;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.infrastructure.dao.IDocumentVersionDao;
import com.hector.infrastructure.dao.po.DocumentVersion;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文档版本仓储实现。
 */
@Repository
public class DocumentVersionRepository implements IDocumentVersionRepository {

    @Resource
    private IDocumentVersionDao documentVersionDao;

    @Override
    public DocumentVersionEntity save(DocumentVersionEntity documentVersionEntity) {
        DocumentVersion documentVersion = toPo(documentVersionEntity);
        documentVersionDao.insert(documentVersion);
        return toEntity(documentVersion);
    }

    @Override
    public void updateParseResult(DocumentVersionEntity documentVersionEntity) {
        documentVersionDao.updateParseResult(toPo(documentVersionEntity));
    }

    @Override
    public Optional<DocumentVersionEntity> findByDocumentIdAndVersionNumber(Long documentId, Integer versionNumber) {
        DocumentVersion documentVersion = documentVersionDao.queryByDocumentIdAndVersionNumber(documentId, versionNumber);
        return documentVersion == null ? Optional.empty() : Optional.of(toEntity(documentVersion));
    }

    @Override
    public List<DocumentVersionEntity> listByDocumentId(Long documentId) {
        return documentVersionDao.queryByDocumentId(documentId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private DocumentVersion toPo(DocumentVersionEntity documentVersionEntity) {
        return DocumentVersion.builder()
                .id(documentVersionEntity.getId())
                .documentId(documentVersionEntity.getDocumentId())
                .versionNumber(documentVersionEntity.getVersionNumber())
                .fileId(documentVersionEntity.getFileId())
                .contentHash(documentVersionEntity.getContentHash())
                .extractedFileId(documentVersionEntity.getExtractedFileId())
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
                .fileId(documentVersion.getFileId())
                .contentHash(documentVersion.getContentHash())
                .extractedFileId(documentVersion.getExtractedFileId())
                .parseStatus(DocumentParseStatusVO.valueOf(documentVersion.getParseStatus()))
                .parserVersion(documentVersion.getParserVersion())
                .parsedAt(documentVersion.getParsedAt())
                .changeSummary(documentVersion.getChangeSummary())
                .createdByUserId(documentVersion.getCreatedByUserId())
                .createdAt(documentVersion.getCreatedAt())
                .build();
    }
}

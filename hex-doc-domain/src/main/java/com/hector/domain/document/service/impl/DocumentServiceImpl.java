package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentParseStatusVO;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文档领域服务实现。
 * 将上传文件转换为租户内的文档聚合，并在同一事务中建立初始版本。
 */
@Service
public class DocumentServiceImpl implements IDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    private static final String DOCUMENT_CODE_PREFIX = "doc_";

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Override
    @Transactional
    public DocumentEntity createUploadedDocument(Long tenantId, Long ownerUserId, Long sourceFileId,
                                                 String contentHash, String title) {
        log.info("文档领域服务-创建上传文档开始 tenantId:{} ownerUserId:{} sourceFileId:{}",
                tenantId, ownerUserId, sourceFileId);
        validateCreateCommand(tenantId, ownerUserId, sourceFileId, title);

        // 1. 先创建聚合根，当前版本固定从 1 开始。
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity documentEntity = documentRepository.save(DocumentEntity.builder()
                .documentCode(generateDocumentCode())
                .tenantId(tenantId)
                .ownerUserId(ownerUserId)
                .sourceFileId(sourceFileId)
                .title(title.trim())
                .documentType(DocumentTypeVO.UPLOADED)
                .status(DocumentStatusVO.ACTIVE)
                .currentVersion(1)
                .createdAt(now)
                .updatedAt(now)
                .build());

        // 2. 初始版本只记录文件快照，解析结果由 RabbitMQ 消费者异步回写。
        documentVersionRepository.save(DocumentVersionEntity.builder()
                .documentId(documentEntity.getId())
                .versionNumber(1)
                .fileId(sourceFileId)
                .contentHash(contentHash)
                .parseStatus(DocumentParseStatusVO.PENDING)
                .createdByUserId(ownerUserId)
                .createdAt(now)
                .build());
        log.info("文档领域服务-创建上传文档完成 documentId:{} documentCode:{} tenantId:{}",
                documentEntity.getId(), documentEntity.getDocumentCode(), documentEntity.getTenantId());
        return documentEntity;
    }

    @Override
    public List<DocumentEntity> listActiveDocuments(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_TENANT_ID);
        }
        log.info("文档领域服务-查询活跃文档列表开始 tenantId:{}", tenantId);
        List<DocumentEntity> documentEntities = documentRepository.listByTenantId(tenantId).stream()
                .filter(DocumentEntity::isActive)
                .collect(Collectors.toList());
        log.info("文档领域服务-查询活跃文档列表完成 tenantId:{} documentCount:{}", tenantId, documentEntities.size());
        return documentEntities;
    }

    @Override
    public DocumentEntity getActiveDocument(Long tenantId, String documentCode) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_TENANT_ID);
        }
        if (documentCode == null || documentCode.isBlank()) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        log.info("文档领域服务-查询活跃文档开始 tenantId:{} documentCode:{}", tenantId, documentCode);
        DocumentEntity documentEntity = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!tenantId.equals(documentEntity.getTenantId())) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        if (!documentEntity.isActive()) {
            throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
        }
        log.info("文档领域服务-查询活跃文档完成 documentId:{} documentCode:{}", documentEntity.getId(), documentEntity.getDocumentCode());
        return documentEntity;
    }

    @Override
    public DocumentVersionEntity getCurrentVersion(DocumentEntity documentEntity) {
        log.info("文档领域服务-查询当前版本开始 documentId:{} currentVersion:{}",
                documentEntity.getId(), documentEntity.getCurrentVersion());
        DocumentVersionEntity documentVersionEntity = documentVersionRepository
                .findByDocumentIdAndVersionNumber(documentEntity.getId(), documentEntity.getCurrentVersion())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        log.info("文档领域服务-查询当前版本完成 documentId:{} versionId:{} versionNumber:{}",
                documentEntity.getId(), documentVersionEntity.getId(), documentVersionEntity.getVersionNumber());
        return documentVersionEntity;
    }

    private void validateCreateCommand(Long tenantId, Long ownerUserId, Long sourceFileId, String title) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_TENANT_ID);
        }
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_USER_ID);
        }
        if (sourceFileId == null || sourceFileId <= 0) {
            throw new BusinessException(ResponseCode.FILE_NOT_FOUND);
        }
        if (title == null || title.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_TITLE);
        }
    }

    private String generateDocumentCode() {
        for (int i = 0; i < 10; i++) {
            String documentCode = DOCUMENT_CODE_PREFIX + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12)
                    .toLowerCase(Locale.ROOT);
            if (documentRepository.findByDocumentCode(documentCode).isEmpty()) {
                return documentCode;
            }
        }
        throw new BusinessException(ResponseCode.SYSTEM_ERROR);
    }
}

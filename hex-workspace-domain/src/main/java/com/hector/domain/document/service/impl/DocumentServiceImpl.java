package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.model.valobj.DocumentParseStatusVO;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.document.model.entity.DocumentContentSnapshotEntity;
import com.hector.domain.document.service.IDocumentContentSnapshotService;
import com.hector.domain.folder.service.IFolderService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 文档领域服务实现。
 * 负责 Workspace 内文档及其版本的创建和状态维护。
 */
@Service
public class DocumentServiceImpl implements IDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentServiceImpl.class);
    private static final String DOCUMENT_CODE_PREFIX = "doc_";

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Resource
    private IDocumentContentSnapshotService documentContentSnapshotService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IFolderService folderService;

    @Override
    public DocumentEntity importDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                         String contentType, byte[] content) {
        log.info("文档领域服务-导入文档开始 workspaceId:{} ownerUserId:{} parentId:{} fileName:{}",
                workspaceId, ownerUserId, parentId, fileName);
        validateCreateCommand(workspaceId, ownerUserId, parentId, fileName);
        String normalizedFileName = normalizeFileName(fileName, false);
        DocumentContentSnapshotEntity snapshot = documentContentSnapshotService.prepareImportedSnapshot(
                workspaceId, ownerUserId, normalizedFileName, contentType, content);

        // 1. 在同一个编码式事务中保存待写入快照、文档聚合和版本 1。
        DocumentWriteResult result = executeInTransaction(() -> createInitialDocument(
                workspaceId, ownerUserId, parentId, normalizedFileName, DocumentTypeVO.LOCAL_UPLOADED, snapshot));

        // 2. 事务提交后才写 MinIO，避免数据库回滚时留下没有元数据的对象。
        finalizeSnapshotWrite(result, content, null);
        log.info("文档领域服务-导入文档完成 documentId:{} documentCode:{} workspaceId:{}",
                result.document().getId(), result.document().getDocumentCode(), workspaceId);
        return result.document();
    }

    @Override
    public DocumentEntity createMarkdownDocument(Long workspaceId, Long ownerUserId, Long parentId,
                                                 String fileName, String content) {
        log.info("文档领域服务-新建Markdown文档开始 workspaceId:{} ownerUserId:{} parentId:{}",
                workspaceId, ownerUserId, parentId);
        String normalizedFileName = normalizeFileName(fileName, true);
        String normalizedContent = content == null ? "" : content;
        DocumentContentSnapshotEntity snapshot = documentContentSnapshotService.prepareDerivedSnapshot(
                workspaceId, ownerUserId, normalizedFileName,
                "text/markdown; charset=utf-8", normalizedContent.getBytes(StandardCharsets.UTF_8));
        DocumentWriteResult result = executeInTransaction(() -> createInitialDocument(
                workspaceId, ownerUserId, parentId, normalizedFileName, DocumentTypeVO.PLATFORM_CREATED, snapshot));
        finalizeSnapshotWrite(result, normalizedContent.getBytes(StandardCharsets.UTF_8), null);
        log.info("文档领域服务-新建Markdown文档完成 workspaceId:{} documentId:{} documentCode:{}",
                workspaceId, result.document().getId(), result.document().getDocumentCode());
        return result.document();
    }

    @Override
    public DocumentEntity editMarkdownDocument(Long workspaceId, String documentCode, Long operatorUserId, String content) {
        log.info("文档领域服务-编辑Markdown文档开始 workspaceId:{} documentCode:{} operatorUserId:{}",
                workspaceId, documentCode, operatorUserId);

        String normalizedContent = content == null ? "" : content;
        byte[] contentBytes = normalizedContent.getBytes(StandardCharsets.UTF_8);
        DocumentWriteResult result = executeInTransaction(() -> {
            // 1. 只允许 Markdown 文档进入编辑流程；当前版本继续保留直到新快照写入成功。
            DocumentEntity document = getActiveDocument(workspaceId, documentCode);
            if (!document.getFileName().toLowerCase(Locale.ROOT).endsWith(".md")) {
                throw new BusinessException(ResponseCode.DOCUMENT_EDIT_UNSUPPORTED);
            }

            // 2. 在事务内保存待写入快照和新版本，再推进当前版本指针。
            DocumentContentSnapshotEntity snapshot = documentContentSnapshotService.prepareDerivedSnapshot(
                    workspaceId, operatorUserId, document.getFileName(),
                    "text/markdown; charset=utf-8", contentBytes);
            DocumentContentSnapshotEntity savedSnapshot = documentContentSnapshotService.saveSnapshotMetadata(snapshot);
            int previousVersion = document.getCurrentVersion();
            int nextVersion = previousVersion + 1;
            documentVersionRepository.save(DocumentVersionEntity.builder()
                    .documentId(document.getId())
                    .versionNumber(nextVersion)
                    .contentId(savedSnapshot.getId())
                    .contentHash(savedSnapshot.getContentHash())
                    .parseStatus(DocumentParseStatusVO.NOT_REQUESTED)
                    .createdByUserId(operatorUserId)
                    .createdAt(LocalDateTime.now())
                    .build());
            document.switchCurrentVersion(nextVersion);
            documentRepository.update(document);
            return new DocumentWriteResult(document, savedSnapshot, previousVersion);
        });
        finalizeSnapshotWrite(result, contentBytes, result.previousVersion());
        log.info("文档领域服务-编辑Markdown文档完成 workspaceId:{} documentCode:{} versionNumber:{}",
                workspaceId, documentCode, result.document().getCurrentVersion());
        return result.document();
    }

    @Override
    public byte[] readCurrentContent(Long workspaceId, String documentCode) {
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        return documentContentSnapshotService.readSnapshotContent(workspaceId, getCurrentVersion(document).getContentId());
    }

    @Override
    public DocumentEntity renameDocument(Long workspaceId, String documentCode, String fileName) {
        log.info("文档领域服务-重命名文档开始 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        DocumentEntity document = executeInTransaction(() -> {
            DocumentEntity activeDocument = getActiveDocument(workspaceId, documentCode);
            String normalized = normalizeFileName(fileName,
                    activeDocument.getDocumentType() == DocumentTypeVO.PLATFORM_CREATED);
            assertFileNameAvailable(workspaceId, activeDocument.getParentId(), normalized, activeDocument.getId());
            activeDocument.rename(normalized);
            documentRepository.update(activeDocument);
            return activeDocument;
        });
        log.info("文档领域服务-重命名文档完成 workspaceId:{} documentCode:{} fileName:{}",
                workspaceId, documentCode, document.getFileName());
        return document;
    }

    @Override
    public DocumentEntity moveDocument(Long workspaceId, String documentCode, Long targetParentId) {
        log.info("文档领域服务-移动文档开始 workspaceId:{} documentCode:{} targetParentId:{}",
                workspaceId, documentCode, targetParentId);
        DocumentEntity document = executeInTransaction(() -> {
            DocumentEntity activeDocument = getActiveDocument(workspaceId, documentCode);
            folderService.getActiveFolder(workspaceId, targetParentId);
            assertFileNameAvailable(workspaceId, targetParentId, activeDocument.getFileName(), activeDocument.getId());
            activeDocument.moveTo(targetParentId);
            documentRepository.update(activeDocument);
            return activeDocument;
        });
        log.info("文档领域服务-移动文档完成 workspaceId:{} documentCode:{} targetParentId:{}",
                workspaceId, documentCode, targetParentId);
        return document;
    }

    @Override
    public DocumentEntity deleteDocument(Long workspaceId, String documentCode) {
        log.info("文档领域服务-删除文档开始 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        DocumentEntity document = executeInTransaction(() -> {
            DocumentEntity activeDocument = getActiveDocument(workspaceId, documentCode);
            activeDocument.delete();
            documentRepository.update(activeDocument);
            return activeDocument;
        });
        log.info("文档领域服务-删除文档完成 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        return document;
    }

    @Override
    public List<DocumentEntity> listActiveDocumentsByParentId(Long workspaceId, Long parentId) {
        folderService.getActiveFolder(workspaceId, parentId);
        return documentRepository.listActiveByParentId(workspaceId, parentId);
    }

    @Override
    public List<DocumentEntity> listActiveDocuments(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        log.info("文档领域服务-查询活跃文档列表开始 workspaceId:{}", workspaceId);
        List<DocumentEntity> documentEntities = documentRepository.listByWorkspaceId(workspaceId).stream()
                .filter(DocumentEntity::isActive)
                .collect(Collectors.toList());
        log.info("文档领域服务-查询活跃文档列表完成 workspaceId:{} documentCount:{}", workspaceId, documentEntities.size());
        return documentEntities;
    }

    @Override
    public DocumentEntity getActiveDocument(Long workspaceId, String documentCode) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        if (documentCode == null || documentCode.isBlank()) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        log.info("文档领域服务-查询活跃文档开始 workspaceId:{} documentCode:{}", workspaceId, documentCode);
        DocumentEntity documentEntity = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!workspaceId.equals(documentEntity.getWorkspaceId())) {
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

    /**
     * 在事务内创建初始内容快照、文档和版本 1。
     * 对象存储写入必须在该事务提交后进行，避免数据库回滚后留下无元数据对象。
     */
    private DocumentWriteResult createInitialDocument(Long workspaceId, Long ownerUserId, Long parentId,
                                                      String fileName, DocumentTypeVO documentType,
                                                      DocumentContentSnapshotEntity snapshot) {
        folderService.getActiveFolder(workspaceId, parentId);
        assertFileNameAvailable(workspaceId, parentId, fileName, null);
        DocumentContentSnapshotEntity savedSnapshot = documentContentSnapshotService.saveSnapshotMetadata(snapshot);
        LocalDateTime now = LocalDateTime.now();
        DocumentEntity document = documentRepository.save(DocumentEntity.builder()
                .documentCode(generateDocumentCode())
                .workspaceId(workspaceId)
                .parentId(parentId)
                .ownerUserId(ownerUserId)
                .initialContentId(savedSnapshot.getId())
                .title(resolveTitle(fileName))
                .fileName(fileName)
                .documentType(documentType)
                .status(DocumentStatusVO.ACTIVE)
                .currentVersion(1)
                .createdAt(now)
                .updatedAt(now)
                .build());
        documentVersionRepository.save(DocumentVersionEntity.builder()
                .documentId(document.getId())
                .versionNumber(1)
                .contentId(savedSnapshot.getId())
                .contentHash(savedSnapshot.getContentHash())
                .parseStatus(DocumentParseStatusVO.NOT_REQUESTED)
                .createdByUserId(ownerUserId)
                .createdAt(now)
                .build());
        return new DocumentWriteResult(document, savedSnapshot, null);
    }

    /**
     * 将已经提交元数据的内容写入 MinIO，并以新的编码式事务回写最终状态。
     * MinIO 写入失败时，创建操作会逻辑删除文档；编辑操作会恢复原当前版本指针。
     */
    private void finalizeSnapshotWrite(DocumentWriteResult result, byte[] content, Integer previousVersion) {
        try {
            documentContentSnapshotService.writeSnapshotObject(result.snapshot(), content);
            executeInTransaction(() -> {
                documentContentSnapshotService.markSnapshotSucceeded(
                        result.snapshot().getWorkspaceId(), result.snapshot().getId());
                return Boolean.TRUE;
            });
        } catch (Exception exception) {
            executeInTransaction(() -> {
                documentContentSnapshotService.markSnapshotFailed(
                        result.snapshot().getWorkspaceId(), result.snapshot().getId());
                DocumentEntity document = documentRepository.findById(result.document().getId())
                        .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
                if (previousVersion == null) {
                    document.delete();
                } else {
                    document.switchCurrentVersion(previousVersion);
                }
                documentRepository.update(document);
                return Boolean.TRUE;
            });
            log.error("文档领域服务-写入内容快照失败 documentId:{} snapshotId:{} error:{}",
                    result.document().getId(), result.snapshot().getId(), exception.getMessage(), exception);
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_STORE_FAILED);
        }
    }

    /** 使用 TransactionTemplate 显式定义 MySQL 事务边界。 */
    private <T> T executeInTransaction(Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) {
            throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        }
        return result;
    }

    private record DocumentWriteResult(DocumentEntity document, DocumentContentSnapshotEntity snapshot,
                                       Integer previousVersion) {
    }

    private void validateCreateCommand(Long workspaceId, Long ownerUserId, Long parentId, String fileName) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_USER_ID);
        }
        if (parentId == null || parentId <= 0) {
            throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        }
        normalizeFileName(fileName, false);
    }

    private String normalizeFileName(String fileName, boolean markdown) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_TITLE);
        }
        String normalized = fileName.trim().replace('\\', '_').replace('/', '_');
        if (markdown && !normalized.toLowerCase(Locale.ROOT).endsWith(".md")) {
            normalized += ".md";
        }
        return normalized;
    }

    private String resolveTitle(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private void assertFileNameAvailable(Long workspaceId, Long parentId, String fileName, Long selfId) {
        documentRepository.findActiveByParentIdAndFileName(workspaceId, parentId, fileName)
                .filter(found -> !found.getId().equals(selfId))
                .ifPresent(found -> {
                    throw new BusinessException(ResponseCode.DOCUMENT_NAME_DUPLICATE);
                });
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

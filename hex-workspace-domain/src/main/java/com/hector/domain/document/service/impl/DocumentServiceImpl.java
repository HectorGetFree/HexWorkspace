package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;
import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentStorageTypeVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.model.valobj.DocumentUploadOutboxStatusVO;
import com.hector.domain.document.model.valobj.DocumentUploadTaskStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.folder.service.IFolderService;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 文档领域服务。文档是唯一内容元数据主体，不维护版本、快照或解析状态。 */
@Service
public class DocumentServiceImpl implements IDocumentService {
    private static final String DOCUMENT_CODE_PREFIX = "doc_";

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentStorageRepository documentStorageRepository;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IFolderService folderService;

    @Resource
    private IDocumentUploadTaskRepository documentUploadTaskRepository;

    @Resource
    private IDocumentUploadOutboxEventRepository documentUploadOutboxEventRepository;

    @Override
    public DocumentEntity importDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                         String contentType, InputStream content, long contentSize, String idempotencyKey) {
        validateCreateCommand(workspaceId, ownerUserId, parentId, fileName);
        if (content == null || contentSize <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_CONTENT);
        }
        String normalizedFileName = normalizeFileName(fileName, false);
        String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
        String normalizedContentType = normalizeContentType(contentType);
        return createDocument(workspaceId, ownerUserId, parentId, normalizedFileName,
                DocumentTypeVO.LOCAL_UPLOADED, normalizedContentType, content, contentSize, normalizedKey);
    }

    @Override
    public DocumentEntity createMarkdownDocument(Long workspaceId, Long ownerUserId, Long parentId,
                                                 String fileName, String content) {
        String normalized = normalizeFileName(fileName, true);
        byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        return createDocument(workspaceId, ownerUserId, parentId, normalized, DocumentTypeVO.PLATFORM_CREATED,
                "text/markdown; charset=utf-8", new ByteArrayInputStream(contentBytes), contentBytes.length, null);
    }

    @Override
    public DocumentEntity editMarkdownDocument(Long workspaceId, String documentCode, Long operatorUserId, String content) {
        byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);

        // 1. 用编码式事务登记 WRITING 状态与待写对象路径，保证 MinIO 对象始终可追溯到文档元数据。
        DocumentEntity writing = executeInTransaction(() -> {
            DocumentEntity active = getActiveDocument(workspaceId, documentCode);
            if (!active.getFileName().toLowerCase(Locale.ROOT).endsWith(".md")) {
                throw new BusinessException(ResponseCode.DOCUMENT_EDIT_UNSUPPORTED);
            }
            DocumentEntity next = copyWithContent(active, DocumentStatusVO.WRITING,
                    newStoragePath(workspaceId, active.getDocumentCode()), "text/markdown; charset=utf-8", contentBytes);
            documentRepository.update(next);
            return next;
        });

        // 2. MySQL 提交后写入 MinIO；异常中断会留下可扫描恢复的 WRITING 文档，而非孤儿对象。
        writeObjectAndActivate(writing, contentBytes);
        return getActiveDocument(workspaceId, documentCode);
    }

    @Override
    public byte[] readCurrentContent(Long workspaceId, String documentCode) {
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        return documentStorageRepository.download(document.getStoragePath());
    }

    @Override
    public DocumentEntity renameDocument(Long workspaceId, String documentCode, String fileName) {
        return executeInTransaction(() -> {
            DocumentEntity document = getActiveDocument(workspaceId, documentCode);
            String normalized = normalizeFileName(fileName, document.getDocumentType() == DocumentTypeVO.PLATFORM_CREATED);
            assertFileNameAvailable(workspaceId, document.getParentId(), normalized, document.getId());
            document.rename(normalized);
            documentRepository.update(document);
            return document;
        });
    }

    @Override
    public DocumentEntity moveDocument(Long workspaceId, String documentCode, Long targetParentId) {
        return executeInTransaction(() -> {
            DocumentEntity document = getActiveDocument(workspaceId, documentCode);
            folderService.getActiveFolder(workspaceId, targetParentId);
            assertFileNameAvailable(workspaceId, targetParentId, document.getFileName(), document.getId());
            document.moveTo(targetParentId);
            documentRepository.update(document);
            return document;
        });
    }

    @Override
    public DocumentEntity deleteDocument(Long workspaceId, String documentCode) {
        return executeInTransaction(() -> {
            DocumentEntity document = getActiveDocument(workspaceId, documentCode);
            document.delete();
            documentRepository.update(document);
            return document;
        });
    }

    @Override
    public List<DocumentEntity> listVisibleDocumentsByParentId(Long workspaceId, Long parentId) {
        folderService.getActiveFolder(workspaceId, parentId);
        return documentRepository.listVisibleByParentId(workspaceId, parentId);
    }

    @Override
    public List<DocumentEntity> listVisibleDocuments(Long workspaceId) {
        validateWorkspaceId(workspaceId);
        return documentRepository.listByWorkspaceId(workspaceId).stream().filter(DocumentEntity::isVisibleInWorkspace)
                .collect(Collectors.toList());
    }

    @Override
    public DocumentEntity getActiveDocument(Long workspaceId, String documentCode) {
        validateWorkspaceId(workspaceId);
        if (documentCode == null || documentCode.isBlank()) throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        DocumentEntity document = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!workspaceId.equals(document.getWorkspaceId())) throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        if (!document.isActive()) throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
        return document;
    }

    /** 创建流程：流式写临时对象并计算摘要，再原子登记文档、上传任务和 Outbox 事件。 */
    private DocumentEntity createDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                          DocumentTypeVO type, String contentType, InputStream content, long contentSize,
                                          String idempotencyKey) {
        String documentCode = generateDocumentCode();
        String stagingPath = newStagingPath(documentCode);
        String targetPath = newStoragePath(workspaceId, documentCode);
        String normalizedContentType = normalizeContentType(contentType);
        boolean documentRegistered = false;
        String contentHash = null;

        try {
            // 1. 请求内容以流方式写入临时对象，同时计算摘要，不将完整文件读入 JVM 内存。
            contentHash = uploadStagingContent(stagingPath, content, contentSize, normalizedContentType);

            // 2. 已存在同一幂等请求时复用既有文档，并在 finally 中删除本次重复写入的临时对象。
            if (idempotencyKey != null) {
                DocumentEntity existing = documentRepository.findByUploadIdempotencyKey(
                        workspaceId, ownerUserId, idempotencyKey).orElse(null);
                if (existing != null) {
                    return assertAndReuseUploadDocument(
                            existing, parentId, fileName, normalizedContentType, contentHash);
                }
            }

            // 3. 文档、任务和 Outbox 事件必须在同一个 MySQL 事务中提交。
            DocumentEntity document = registerDocument(workspaceId, ownerUserId, parentId, fileName, type,
                    normalizedContentType, contentSize, contentHash, idempotencyKey, documentCode, stagingPath, targetPath);
            documentRegistered = true;
            return document;
        } catch (RuntimeException exception) {
            if (idempotencyKey != null && contentHash != null) {
                DocumentEntity duplicate = documentRepository.findByUploadIdempotencyKey(
                        workspaceId, ownerUserId, idempotencyKey).orElse(null);
                if (duplicate != null) {
                    return assertAndReuseUploadDocument(duplicate, parentId, fileName,
                            normalizedContentType, contentHash);
                }
            }
            throw exception;
        } finally {
            if (!documentRegistered) {
                deleteStagingObjectQuietly(stagingPath);
            }
        }
    }

    private DocumentEntity registerDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                            DocumentTypeVO type, String contentType, long contentSize, String contentHash,
                                            String idempotencyKey, String documentCode, String stagingPath, String targetPath) {
        return executeInTransaction(() -> {
            folderService.getActiveFolder(workspaceId, parentId);
            assertFileNameAvailable(workspaceId, parentId, fileName, null);

            LocalDateTime now = LocalDateTime.now();
            DocumentEntity document = documentRepository.save(DocumentEntity.builder()
                    .documentCode(documentCode)
                    .workspaceId(workspaceId)
                    .parentId(parentId)
                    .ownerUserId(ownerUserId)
                    .title(resolveTitle(fileName))
                    .fileName(fileName)
                    .documentType(type)
                    .status(DocumentStatusVO.CREATING)
                    .storageType(DocumentStorageTypeVO.MINIO)
                    .bucketName(documentStorageRepository.getBucketName())
                    .storagePath(targetPath)
                    .contentType(contentType)
                    .contentSize(contentSize)
                    .contentHash(contentHash)
                    .idempotencyKey(idempotencyKey)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            String taskCode = newTaskCode();
            documentUploadTaskRepository.save(DocumentUploadTaskEntity.builder()
                    .taskCode(taskCode)
                    .documentId(document.getId())
                    .stagingPath(stagingPath)
                    .targetPath(targetPath)
                    .contentType(contentType)
                    .status(DocumentUploadTaskStatusVO.PENDING)
                    .retryCount(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            documentUploadOutboxEventRepository.save(DocumentUploadOutboxEventEntity.builder()
                    .eventCode(newEventCode())
                    .taskCode(taskCode)
                    .status(DocumentUploadOutboxStatusVO.PENDING)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            return document;
        });
    }

    private String uploadStagingContent(String stagingPath, InputStream content, long contentSize, String contentType) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            DigestInputStream digestInputStream = new DigestInputStream(content, messageDigest);
            documentStorageRepository.upload(stagingPath, digestInputStream, contentSize, contentType);
            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_STORE_FAILED);
        }
    }

    private void deleteStagingObjectQuietly(String stagingPath) {
        try {
            documentStorageRepository.delete(stagingPath);
        } catch (Exception ignored) {
            // 当前异常优先按原始失败返回；残留对象由 staging 孤儿清理任务处理。
        }
    }

    /** 写入成功才提交 ACTIVE；失败不伪造成功，保留中间状态给恢复任务处理。 */
    private void writeObjectAndActivate(DocumentEntity document, byte[] content) {
        try {
            documentStorageRepository.upload(document.getStoragePath(), content, document.getContentType());
            executeInTransaction(() -> {
                DocumentEntity persisted = documentRepository.findById(document.getId())
                        .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
                persisted.activate();
                documentRepository.update(persisted);
                return persisted;
            });
        } catch (Exception exception) {
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_STORE_FAILED);
        }
    }

    private DocumentEntity copyWithContent(DocumentEntity source, DocumentStatusVO status, String path,
                                           String contentType, byte[] content) {
        return DocumentEntity.builder().id(source.getId()).documentCode(source.getDocumentCode())
                .workspaceId(source.getWorkspaceId()).parentId(source.getParentId()).ownerUserId(source.getOwnerUserId())
                .title(source.getTitle()).fileName(source.getFileName()).documentType(source.getDocumentType()).status(status)
                .storageType(DocumentStorageTypeVO.MINIO).bucketName(documentStorageRepository.getBucketName()).storagePath(path)
                .contentType(normalizeContentType(contentType)).contentSize((long) content.length).contentHash(hash(content))
                .createdAt(source.getCreatedAt()).updatedAt(LocalDateTime.now()).build();
    }

    private <T> T executeInTransaction(Supplier<T> action) {
        T result = transactionTemplate.execute(status -> action.get());
        if (result == null) throw new BusinessException(ResponseCode.SYSTEM_ERROR);
        return result;
    }

    private void validateCreateCommand(Long workspaceId, Long ownerUserId, Long parentId, String fileName) {
        validateWorkspaceId(workspaceId);
        if (ownerUserId == null || ownerUserId <= 0) throw new BusinessException(ResponseCode.EMPTY_USER_ID);
        if (parentId == null || parentId <= 0) throw new BusinessException(ResponseCode.FOLDER_NOT_FOUND);
        normalizeFileName(fileName, false);
    }

    private void validateWorkspaceId(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
    }

    private String normalizeFileName(String fileName, boolean markdown) {
        if (fileName == null || fileName.isBlank()) throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_TITLE);
        String normalized = fileName.trim().replace('\\', '_').replace('/', '_');
        return markdown && !normalized.toLowerCase(Locale.ROOT).endsWith(".md") ? normalized + ".md" : normalized;
    }

    private String resolveTitle(String fileName) {
        int index = fileName.lastIndexOf('.');
        return index > 0 ? fileName.substring(0, index) : fileName;
    }

    private void assertFileNameAvailable(Long workspaceId, Long parentId, String fileName, Long selfId) {
        documentRepository.findActiveByParentIdAndFileName(workspaceId, parentId, fileName)
                .filter(found -> !found.getId().equals(selfId))
                .ifPresent(found -> { throw new BusinessException(ResponseCode.DOCUMENT_NAME_DUPLICATE); });
    }

    private String newStoragePath(Long workspaceId, String documentCode) {
        return "workspace/" + workspaceId + "/documents/" + documentCode + "/" + UUID.randomUUID();
    }

    private String newStagingPath(String documentCode) {
        return "staging/documents/" + documentCode + "/" + UUID.randomUUID();
    }

    private String newTaskCode() {
        return "upload_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String newEventCode() {
        return "outbox_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        return idempotencyKey.trim();
    }

    private DocumentEntity assertAndReuseUploadDocument(DocumentEntity document, Long parentId, String fileName,
                                                         String contentType, String contentHash) {
        boolean sameRequest = parentId.equals(document.getParentId())
                && fileName.equals(document.getFileName())
                && contentType.equals(document.getContentType())
                && contentHash.equals(document.getContentHash())
                && DocumentTypeVO.LOCAL_UPLOADED == document.getDocumentType();
        if (!sameRequest) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        return document;
    }

    private String hash(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception exception) { throw new BusinessException(ResponseCode.SYSTEM_ERROR); }
    }

    private String generateDocumentCode() {
        for (int i = 0; i < 10; i++) {
            String code = DOCUMENT_CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
            if (documentRepository.findByDocumentCode(code).isEmpty()) return code;
        }
        throw new BusinessException(ResponseCode.SYSTEM_ERROR);
    }
}

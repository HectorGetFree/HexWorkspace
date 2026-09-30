package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentEntity;
import com.hector.domain.document.model.entity.DocumentUploadOutboxEventEntity;
import com.hector.domain.document.model.entity.DocumentUploadTaskEntity;
import com.hector.domain.document.model.entity.DocumentVersionEntity;
import com.hector.domain.document.adapter.port.IDocumentVersionTextReader;
import com.hector.domain.document.model.valobj.DocumentStatusVO;
import com.hector.domain.document.model.valobj.DocumentTypeVO;
import com.hector.domain.document.model.valobj.DocumentReadResultVO;
import com.hector.domain.document.model.valobj.DocumentMutationResultVO;
import com.hector.domain.document.model.valobj.DocumentUploadOutboxStatusVO;
import com.hector.domain.document.model.valobj.DocumentUploadTaskStatusVO;
import com.hector.domain.document.repository.IDocumentRepository;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import com.hector.domain.document.repository.IDocumentUploadOutboxEventRepository;
import com.hector.domain.document.repository.IDocumentUploadTaskRepository;
import com.hector.domain.document.repository.IDocumentVersionRepository;
import com.hector.domain.document.service.IDocumentService;
import com.hector.domain.document.service.IDocumentUploadTaskService;
import com.hector.domain.folder.service.IFolderService;
import com.hector.domain.knowledge.service.IKnowledgeIndexJobService;
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

/** 文档领域服务，负责文档元数据、当前版本指针及内容保存编排。 */
@Service
public class DocumentServiceImpl implements IDocumentService {
    private static final String DOCUMENT_CODE_PREFIX = "doc_";
    private static final int SYNCHRONOUS_EDIT_LEASE_MINUTES = 10;
    private static final int MAX_READ_LINE_COUNT = 500;

    @Resource
    private IDocumentRepository documentRepository;

    @Resource
    private IDocumentStorageRepository documentStorageRepository;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private IFolderService folderService;

    @Resource
    private IKnowledgeIndexJobService knowledgeIndexJobService;

    @Resource
    private IDocumentUploadTaskRepository documentUploadTaskRepository;

    @Resource
    private IDocumentUploadOutboxEventRepository documentUploadOutboxEventRepository;

    @Resource
    private IDocumentVersionRepository documentVersionRepository;

    @Resource
    private IDocumentVersionTextReader documentVersionTextReader;

    @Resource
    private IDocumentUploadTaskService documentUploadTaskService;

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

        // 1. 先基于当前正式版本生成唯一版本路径；历史版本对象绝不覆盖。
        DocumentEntity active = getActiveDocument(workspaceId, documentCode);
        if (!active.getFileName().toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new BusinessException(ResponseCode.DOCUMENT_EDIT_UNSUPPORTED);
        }
        long nextVersionNo = active.getCurrentVersionNo() + 1;
        String stagingPath = newStagingPath(documentCode);
        String targetPath = newVersionStoragePath(workspaceId, documentCode, nextVersionNo);
        String contentType = "text/markdown; charset=utf-8";
        String executionToken = UUID.randomUUID().toString();
        boolean taskRegistered = false;

        try {
            // 2. 先写 staging 并计算摘要；写入后才登记任务，避免 MySQL 记录指向不存在的对象。
            String contentHash = uploadStagingContent(stagingPath, new ByteArrayInputStream(contentBytes),
                    contentBytes.length, contentType);
            String taskCode = registerContentVersionSave(workspaceId, documentCode, active.getCurrentVersionNo(),
                    nextVersionNo, operatorUserId, stagingPath, targetPath, contentType, contentBytes.length, contentHash,
                    executionToken);
            taskRegistered = true;

            // 3. 保持现有编辑接口的同步成功语义；Outbox 仍保留为进程异常后的异步兜底。
            documentUploadTaskService.executeOwned(taskCode, executionToken);
            return getActiveDocument(workspaceId, documentCode);
        } finally {
            if (!taskRegistered) {
                deleteStagingObjectQuietly(stagingPath);
            }
        }
    }

    @Override
    public DocumentMutationResultVO updateMarkdownDocument(Long workspaceId, Long operatorUserId, Long documentId,
                                                           Long expectedVersionId, String content) {
        DocumentEntity active = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!workspaceId.equals(active.getWorkspaceId()) || !operatorUserId.equals(active.getOwnerUserId())
                || !active.isActive()) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        DocumentVersionEntity expectedVersion = documentVersionRepository
                .findByDocumentIdAndVersionNo(documentId, expectedVersionId)
                .orElseThrow(() -> new BusinessException(ResponseCode.VERSION_NOT_FOUND));
        if (!expectedVersion.getVersionNo().equals(active.getCurrentVersionNo())) {
            throw new BusinessException(ResponseCode.VERSION_CONFLICT);
        }
        byte[] contentBytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        String stagingPath = newStagingPath(active.getDocumentCode());
        long targetVersionNo = active.getCurrentVersionNo() + 1;
        String targetPath = newVersionStoragePath(workspaceId, active.getDocumentCode(), targetVersionNo);
        String executionToken = UUID.randomUUID().toString();
        boolean taskRegistered = false;
        try {
            String contentHash = uploadStagingContent(stagingPath, new ByteArrayInputStream(contentBytes),
                    contentBytes.length, "text/markdown; charset=utf-8");
            String taskCode = registerContentVersionSave(workspaceId, active.getDocumentCode(),
                    active.getCurrentVersionNo(), targetVersionNo, operatorUserId, stagingPath, targetPath,
                    "text/markdown; charset=utf-8", contentBytes.length, contentHash, executionToken);
            taskRegistered = true;
            documentUploadTaskService.executeOwned(taskCode, executionToken);
            DocumentEntity updated = getActiveDocument(workspaceId, active.getDocumentCode());
            if (!targetVersionNoEquals(updated, targetVersionNo)) {
                throw new BusinessException(ResponseCode.DOCUMENT_UPDATE_PENDING);
            }
            DocumentVersionEntity version = documentVersionRepository
                    .findByDocumentIdAndVersionNo(documentId, targetVersionNo)
                    .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_UPDATE_PENDING));
            return new DocumentMutationResultVO(documentId, expectedVersionId, version.getVersionNo());
        } finally {
            if (!taskRegistered) {
                deleteStagingObjectQuietly(stagingPath);
            }
        }
    }

    private boolean targetVersionNoEquals(DocumentEntity document, long targetVersionNo) {
        return document.isActive() && Long.valueOf(targetVersionNo).equals(document.getCurrentVersionNo());
    }

    @Override
    public byte[] readCurrentContent(Long workspaceId, String documentCode) {
        DocumentEntity document = getActiveDocument(workspaceId, documentCode);
        DocumentVersionEntity version = documentVersionRepository
                .findByDocumentIdAndVersionNo(document.getId(), document.getCurrentVersionNo())
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE));
        return documentStorageRepository.download(version.getStoragePath());
    }

    @Override
    public DocumentReadResultVO readVersionContent(Long workspaceId, Long userId, Long documentId, Long versionNo,
                                                   int startLine, int lineCount) {
        if (documentId == null || documentId <= 0 || versionNo == null || versionNo <= 0
                || startLine < 1 || lineCount < 1 || lineCount > MAX_READ_LINE_COUNT) {
            throw new IllegalArgumentException("文档版本或读取范围无效");
        }
        DocumentEntity document = documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!workspaceId.equals(document.getWorkspaceId()) || userId == null
                || !userId.equals(document.getOwnerUserId()) || !document.isActive()) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        // 每次读取都重新检查当前目录状态，历史检索结果不等于永久授权。
        folderService.getActiveFolder(workspaceId, document.getParentId());
        DocumentVersionEntity version = documentVersionRepository
                .findByDocumentIdAndVersionNo(documentId, versionNo)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        String text = documentVersionTextReader.read(documentId, versionNo, version.getContentHash());
        String[] lines = text.split("\\R", -1);
        int from = Math.min(startLine - 1, lines.length);
        int to = Math.min(from + lineCount, lines.length);
        String content = String.join("\n", java.util.Arrays.copyOfRange(lines, from, to));
        int endLine = to == from ? startLine - 1 : to;
        return new DocumentReadResultVO(documentId, versionNo, document.getTitle(), startLine, endLine,
                to < lines.length, content);
    }

    @Override
    public DocumentEntity renameDocument(Long workspaceId, String documentCode, String fileName) {
        return executeInTransaction(() -> {
            DocumentEntity document = getActiveDocument(workspaceId, documentCode);
            String normalized = normalizeFileName(fileName, document.getDocumentType() == DocumentTypeVO.PLATFORM_CREATED);
            assertFileNameAvailable(workspaceId, document.getParentId(), normalized, document.getId());
            if (normalized.equals(document.getFileName())) {
                return document;
            }
            Long expectedIndexInputRevision = document.getIndexInputRevision() == null ? 0L : document.getIndexInputRevision();
            document.renameAndAdvanceIndexRevision(normalized);
            if (!documentRepository.renameAndAdvanceIndexRevisionIfCurrent(document, expectedIndexInputRevision)) {
                throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
            }
            knowledgeIndexJobService.registerAutomatic(document);
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
            DocumentEntity document = getDocumentInWorkspace(workspaceId, documentCode);
            // 1. 重复删除直接返回既有删除态，确保不会刷新 deletedAt。
            if (document.isDeleted()) {
                return document;
            }
            // 2. 当前阶段仅允许已正式生效的文档进入回收站。
            if (!document.isActive()) {
                throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
            }
            // 文档自身 ACTIVE 仍不足以操作；其父目录被删除时，文档必须随子树一并冻结。
            folderService.getActiveFolder(workspaceId, document.getParentId());
            document.delete();
            documentRepository.update(document);
            return document;
        });
    }

    @Override
    public DocumentEntity restoreDocument(Long workspaceId, String documentCode) {
        return executeInTransaction(() -> {
            DocumentEntity document = getDocumentInWorkspace(workspaceId, documentCode);
            // 1. 已恢复的文档直接返回，恢复不会创建版本或改写内容。
            if (document.isActive()) {
                return document;
            }
            // 2. 仅允许回收站中的文档恢复，创建中、写入中和失败文档不在本次范围内处理。
            if (!document.isDeleted()) {
                throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
            }
            // 不能绕过已删除祖先直接恢复文档；需要先恢复其目录树入口。
            folderService.getActiveFolder(workspaceId, document.getParentId());
            document.restore();
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
        return documentRepository.listByWorkspaceId(workspaceId).stream()
                .filter(DocumentEntity::isVisibleInWorkspace)
                .filter(document -> isParentFolderAccessible(workspaceId, document.getParentId()))
                .collect(Collectors.toList());
    }

    @Override
    public DocumentEntity getActiveDocument(Long workspaceId, String documentCode) {
        DocumentEntity document = getDocumentInWorkspace(workspaceId, documentCode);
        if (!document.isActive()) throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
        if (document.getCurrentVersionNo() == null || document.getCurrentVersionNo() <= 0) {
            throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
        }
        // 文档访问必须同时满足自身 ACTIVE 和所属目录祖先链可访问，避免 ID 直连绕过删除子树。
        folderService.getActiveFolder(workspaceId, document.getParentId());
        return document;
    }

    private boolean isParentFolderAccessible(Long workspaceId, Long parentId) {
        try {
            folderService.getActiveFolder(workspaceId, parentId);
            return true;
        } catch (BusinessException ignored) {
            return false;
        }
    }

    /** 查询 Workspace 内文档，不预先过滤删除态，供删除和恢复两个生命周期操作使用。 */
    private DocumentEntity getDocumentInWorkspace(Long workspaceId, String documentCode) {
        validateWorkspaceId(workspaceId);
        if (documentCode == null || documentCode.isBlank()) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        DocumentEntity document = documentRepository.findByDocumentCode(documentCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND));
        if (!workspaceId.equals(document.getWorkspaceId())) {
            throw new BusinessException(ResponseCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    /** 创建流程：流式写临时对象并计算摘要，再原子登记文档、上传任务和 Outbox 事件。 */
    private DocumentEntity createDocument(Long workspaceId, Long ownerUserId, Long parentId, String fileName,
                                          DocumentTypeVO type, String contentType, InputStream content, long contentSize,
                                          String idempotencyKey) {
        String documentCode = generateDocumentCode();
        String stagingPath = newStagingPath(documentCode);
        String targetPath = newVersionStoragePath(workspaceId, documentCode, 1L);
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
                    .currentVersionNo(null)
                    .indexInputRevision(0L)
                    .idempotencyKey(idempotencyKey)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());

            saveUploadTaskAndOutbox(document.getId(), 1L, ownerUserId, stagingPath, targetPath,
                    contentType, contentSize, contentHash, now);
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

    /**
     * 原子登记一次版本保存意图。
     *
     * <p>状态切换、任务和 Outbox 必须共同提交；否则 staging 对象会成为无引用对象，由既有清理任务回收。</p>
     */
    private String registerContentVersionSave(Long workspaceId, String documentCode, Long baseVersionNo, Long nextVersionNo,
                                              Long createdBy, String stagingPath, String targetPath, String contentType,
                                              long contentSize, String contentHash, String executionToken) {
        return executeInTransaction(() -> {
            DocumentEntity document = getActiveDocument(workspaceId, documentCode);
            if (!baseVersionNo.equals(document.getCurrentVersionNo())) {
                throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
            }

            // 1. 通过版本与索引修订双条件 CAS 抢占写入权，阻止并发登记相同后继版本。
            Long expectedIndexInputRevision = document.getIndexInputRevision() == null
                    ? 0L : document.getIndexInputRevision();
            document.startWriting();
            if (!documentRepository.startWritingIfCurrent(document, baseVersionNo, expectedIndexInputRevision)) {
                throw new BusinessException(ResponseCode.DOCUMENT_STATUS_UNAVAILABLE);
            }

            // 2. 保存任务与投递事件，使本次版本转正可由同步调用或 MQ 可靠完成。
            LocalDateTime now = LocalDateTime.now();
            return saveUploadTaskAndOutbox(document.getId(), nextVersionNo, createdBy, stagingPath, targetPath,
                    contentType, contentSize, contentHash, DocumentUploadTaskStatusVO.RUNNING, executionToken,
                    now.plusMinutes(SYNCHRONOUS_EDIT_LEASE_MINUTES), now);
        });
    }

    /** 在同一事务内登记内容转正任务及其可靠投递事件。 */
    private String saveUploadTaskAndOutbox(Long documentId, Long versionNo, Long createdBy, String stagingPath,
                                           String targetPath, String contentType, long contentSize, String contentHash,
                                           LocalDateTime now) {
        return saveUploadTaskAndOutbox(documentId, versionNo, createdBy, stagingPath, targetPath, contentType,
                contentSize, contentHash, DocumentUploadTaskStatusVO.PENDING, null, null, now);
    }

    private String saveUploadTaskAndOutbox(Long documentId, Long versionNo, Long createdBy, String stagingPath,
                                           String targetPath, String contentType, long contentSize, String contentHash,
                                           DocumentUploadTaskStatusVO status, String executionToken,
                                           LocalDateTime runningLeaseUntil, LocalDateTime now) {
        String taskCode = newTaskCode();
        documentUploadTaskRepository.save(DocumentUploadTaskEntity.builder()
                .taskCode(taskCode)
                .documentId(documentId)
                .versionNo(versionNo)
                .createdBy(createdBy)
                .stagingPath(stagingPath)
                .targetPath(targetPath)
                .contentType(contentType)
                .bucketName(documentStorageRepository.getBucketName())
                .contentSize(contentSize)
                .contentHash(contentHash)
                .status(status)
                .executionToken(executionToken)
                .runningLeaseUntil(runningLeaseUntil)
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
        return taskCode;
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

    private String newVersionStoragePath(Long workspaceId, String documentCode, Long versionNo) {
        return "workspace/" + workspaceId + "/documents/" + documentCode + "/versions/" + versionNo;
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
        DocumentVersionEntity initialVersion = documentVersionRepository
                .findByDocumentIdAndVersionNo(document.getId(), 1L)
                .orElse(null);
        DocumentUploadTaskEntity initialTask = initialVersion == null ? documentUploadTaskRepository
                .findByDocumentIdAndVersionNo(document.getId(), 1L)
                .orElse(null) : null;
        String initialContentType = initialVersion != null ? initialVersion.getContentType()
                : initialTask == null ? null : initialTask.getContentType();
        String initialContentHash = initialVersion != null ? initialVersion.getContentHash()
                : initialTask == null ? null : initialTask.getContentHash();
        boolean sameRequest = parentId.equals(document.getParentId())
                && fileName.equals(document.getFileName())
                && contentType.equals(initialContentType)
                && contentHash.equals(initialContentHash)
                && DocumentTypeVO.LOCAL_UPLOADED == document.getDocumentType();
        if (!sameRequest) {
            throw new BusinessException(ResponseCode.ILLEGAL_PARAMETER);
        }
        return document;
    }

    private String generateDocumentCode() {
        for (int i = 0; i < 10; i++) {
            String code = DOCUMENT_CODE_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
            if (documentRepository.findByDocumentCode(code).isEmpty()) return code;
        }
        throw new BusinessException(ResponseCode.SYSTEM_ERROR);
    }
}

package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentContentSnapshotEntity;
import com.hector.domain.document.model.valobj.DocumentContentSnapshotPurposeVO;
import com.hector.domain.document.model.valobj.DocumentContentSnapshotStatusVO;
import com.hector.domain.document.repository.IDocumentContentSnapshotRepository;
import com.hector.domain.document.repository.IDocumentContentSnapshotStorageRepository;
import com.hector.domain.document.service.IDocumentContentSnapshotService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * 文档内容快照服务实现。
 * 负责快照元数据和对象存储操作；事务边界由文档用例以编码方式控制。
 */
@Service
public class DocumentContentSnapshotServiceImpl implements IDocumentContentSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DocumentContentSnapshotServiceImpl.class);
    private static final String SNAPSHOT_CODE_PREFIX = "snapshot_";
    private static final String STORAGE_TYPE_MINIO = "MINIO";

    @Resource
    private IDocumentContentSnapshotRepository snapshotRepository;

    @Resource
    private IDocumentContentSnapshotStorageRepository snapshotStorageRepository;

    @Resource
    private IUserRepository userRepository;

    @Resource
    private IWorkspaceRepository workspaceRepository;

    @Override
    public DocumentContentSnapshotEntity prepareImportedSnapshot(Long workspaceId, Long operatorUserId,
                                                                  String originalName, String contentType,
                                                                  byte[] content) {
        return prepareSnapshot(workspaceId, operatorUserId, originalName, contentType, content,
                DocumentContentSnapshotPurposeVO.ORIGINAL);
    }

    @Override
    public DocumentContentSnapshotEntity prepareDerivedSnapshot(Long workspaceId, Long operatorUserId,
                                                                 String contentName, String contentType,
                                                                 byte[] content) {
        return prepareSnapshot(workspaceId, operatorUserId, contentName, contentType, content,
                DocumentContentSnapshotPurposeVO.DERIVED);
    }

    /**
     * 构造待持久化的内容快照。
     * 该步骤不写 MySQL 或 MinIO，使文档用例可以先完成数据库事务，再执行外部对象存储操作。
     */
    private DocumentContentSnapshotEntity prepareSnapshot(Long workspaceId, Long operatorUserId, String originalName,
                                                           String contentType, byte[] content,
                                                           DocumentContentSnapshotPurposeVO purpose) {
        log.info("文档内容快照服务-准备快照开始 workspaceId:{} operatorUserId:{} purpose:{} originName:{}",
                workspaceId, operatorUserId, purpose, originalName);
        WorkspaceEntity workspace = getActiveWorkspace(workspaceId);
        UserEntity user = getAvailableUser(operatorUserId);
        validateContent(content, originalName, purpose);

        String snapshotCode = generateUniqueSnapshotCode();
        String normalizedName = normalizeContentName(originalName);
        String storagePath = buildStoragePath(workspace.getId(), purpose, snapshotCode, normalizedName);
        LocalDateTime now = LocalDateTime.now();
        DocumentContentSnapshotEntity snapshot = DocumentContentSnapshotEntity.builder()
                .contentCode(snapshotCode)
                .workspaceId(workspace.getId())
                .creatorUserId(user.getId())
                .purpose(purpose)
                .originName(originalName)
                .suffix(extractSuffix(originalName))
                .contentType(normalizeContentType(contentType))
                .contentSize((long) content.length)
                .storageType(STORAGE_TYPE_MINIO)
                .bucketName(snapshotStorageRepository.getBucketName())
                .storagePath(storagePath)
                .contentHash(DigestUtils.md5Hex(content))
                .status(DocumentContentSnapshotStatusVO.PENDING)
                .createdAt(now)
                .updatedAt(now)
                .build();
        log.info("文档内容快照服务-准备快照完成 snapshotCode:{} workspaceId:{}", snapshotCode, workspaceId);
        return snapshot;
    }

    @Override
    public DocumentContentSnapshotEntity saveSnapshotMetadata(DocumentContentSnapshotEntity snapshot) {
        log.info("文档内容快照服务-保存元数据开始 snapshotCode:{} workspaceId:{}",
                snapshot.getContentCode(), snapshot.getWorkspaceId());
        DocumentContentSnapshotEntity savedSnapshot = snapshotRepository.save(snapshot);
        log.info("文档内容快照服务-保存元数据完成 snapshotId:{} snapshotCode:{}",
                savedSnapshot.getId(), savedSnapshot.getContentCode());
        return savedSnapshot;
    }

    @Override
    public void writeSnapshotObject(DocumentContentSnapshotEntity snapshot, byte[] content) {
        log.info("文档内容快照服务-写入对象开始 snapshotId:{} storagePath:{}",
                snapshot.getId(), snapshot.getStoragePath());
        snapshotStorageRepository.upload(snapshot.getStoragePath(), content, snapshot.getContentType());
        log.info("文档内容快照服务-写入对象完成 snapshotId:{} storagePath:{}",
                snapshot.getId(), snapshot.getStoragePath());
    }

    @Override
    public void markSnapshotSucceeded(Long workspaceId, Long snapshotId) {
        DocumentContentSnapshotEntity snapshot = getWorkspaceSnapshot(workspaceId, snapshotId);
        snapshot.markSuccess();
        snapshotRepository.update(snapshot);
    }

    @Override
    public void markSnapshotFailed(Long workspaceId, Long snapshotId) {
        DocumentContentSnapshotEntity snapshot = getWorkspaceSnapshot(workspaceId, snapshotId);
        snapshot.markFailed();
        snapshotRepository.update(snapshot);
    }

    @Override
    public DocumentContentSnapshotEntity getAvailableSnapshot(Long workspaceId, Long snapshotId) {
        DocumentContentSnapshotEntity snapshot = getWorkspaceSnapshot(workspaceId, snapshotId);
        if (snapshot.getStatus() != DocumentContentSnapshotStatusVO.SUCCESS) {
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND);
        }
        return snapshot;
    }

    @Override
    public byte[] readSnapshotContent(Long workspaceId, Long snapshotId) {
        DocumentContentSnapshotEntity snapshot = getAvailableSnapshot(workspaceId, snapshotId);
        return snapshotStorageRepository.download(snapshot.getStoragePath());
    }

    @Override
    public DocumentContentSnapshotEntity deleteSnapshot(Long workspaceId, Long snapshotId) {
        DocumentContentSnapshotEntity snapshot = getWorkspaceSnapshot(workspaceId, snapshotId);
        snapshot.markDeleted();
        snapshotRepository.update(snapshot);
        try {
            snapshotStorageRepository.delete(snapshot.getStoragePath());
        } catch (Exception exception) {
            log.warn("文档内容快照服务-删除对象异常 snapshotId:{} storagePath:{} error:{}",
                    snapshotId, snapshot.getStoragePath(), exception.getMessage(), exception);
        }
        return snapshot;
    }

    private WorkspaceEntity getActiveWorkspace(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        WorkspaceEntity workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ResponseCode.WORKSPACE_NOT_FOUND));
        if (!workspace.isActive()) {
            throw new BusinessException(ResponseCode.WORKSPACE_STATUS_UNAVAILABLE);
        }
        return workspace;
    }

    private UserEntity getAvailableUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_USER_ID);
        }
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }
        return user;
    }

    private DocumentContentSnapshotEntity getWorkspaceSnapshot(Long workspaceId, Long snapshotId) {
        if (snapshotId == null || snapshotId <= 0) {
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND);
        }
        DocumentContentSnapshotEntity snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND));
        if (!workspaceId.equals(snapshot.getWorkspaceId())
                || snapshot.getStatus() == DocumentContentSnapshotStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND);
        }
        return snapshot;
    }

    private void validateContent(byte[] content, String originalName, DocumentContentSnapshotPurposeVO purpose) {
        if (content == null || (content.length == 0 && purpose == DocumentContentSnapshotPurposeVO.ORIGINAL)) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_CONTENT);
        }
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_CONTENT);
        }
    }

    private String generateUniqueSnapshotCode() {
        for (int index = 0; index < 10; index++) {
            String snapshotCode = SNAPSHOT_CODE_PREFIX + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12)
                    .toLowerCase(Locale.ROOT);
            if (snapshotRepository.findByContentCode(snapshotCode).isEmpty()) {
                return snapshotCode;
            }
        }
        throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_STORE_FAILED);
    }

    private String buildStoragePath(Long workspaceId, DocumentContentSnapshotPurposeVO purpose,
                                    String snapshotCode, String contentName) {
        return "workspace/" + workspaceId + "/document-content-snapshot/"
                + purpose.name().toLowerCase(Locale.ROOT) + "/" + snapshotCode + "_" + contentName;
    }

    private String normalizeContentName(String contentName) {
        if (contentName == null || contentName.isBlank()) {
            return "content";
        }
        return contentName.replace('\\', '_').replace('/', '_').trim().replaceAll("\\s+", "_");
    }

    private String normalizeContentType(String contentType) {
        return contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
    }

    private String extractSuffix(String contentName) {
        if (contentName == null) {
            return null;
        }
        int index = contentName.lastIndexOf('.');
        return index < 0 || index == contentName.length() - 1
                ? null : contentName.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}

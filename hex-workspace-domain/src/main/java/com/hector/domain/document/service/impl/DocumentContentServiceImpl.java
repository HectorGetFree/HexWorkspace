package com.hector.domain.document.service.impl;

import com.hector.domain.document.model.entity.DocumentContentEntity;
import com.hector.domain.document.model.valobj.DocumentContentPurposeVO;
import com.hector.domain.document.model.valobj.DocumentContentStatusVO;
import com.hector.domain.document.repository.IDocumentContentRepository;
import com.hector.domain.document.repository.IDocumentObjectStorageRepository;
import com.hector.domain.document.service.IDocumentContentService;
import com.hector.domain.workspace.model.entity.WorkspaceEntity;
import com.hector.domain.workspace.repository.IWorkspaceRepository;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * 文档内容快照服务实现。
 */
@Service
public class DocumentContentServiceImpl implements IDocumentContentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentContentServiceImpl.class);
    private static final String CONTENT_CODE_PREFIX = "content_";
    private static final String STORAGE_TYPE_MINIO = "MINIO";

    @Resource
    private IDocumentContentRepository contentRepository;

    @Resource
    private IDocumentObjectStorageRepository objectStorageRepository;

    @Resource
    private IUserRepository userRepository;

    @Resource
    private IWorkspaceRepository workspaceRepository;

    @Override
    @Transactional
    public DocumentContentEntity storeImportedContent(Long workspaceId, Long operatorUserId, String originalName,
                                                      String contentType, byte[] content) {
        return storeContent(workspaceId, operatorUserId, originalName, contentType, content,
                DocumentContentPurposeVO.ORIGINAL);
    }

    @Override
    @Transactional
    public DocumentContentEntity storeDerivedContent(Long workspaceId, Long operatorUserId, String contentName,
                                                     String contentType, byte[] content) {
        return storeContent(workspaceId, operatorUserId, contentName, contentType, content,
                DocumentContentPurposeVO.DERIVED);
    }

    /**
     * 统一管理文档内容对象写入与元数据落库。
     */
    private DocumentContentEntity storeContent(Long workspaceId, Long operatorUserId, String originalName,
                                               String contentType, byte[] content,
                                               DocumentContentPurposeVO purpose) {
        log.info("文档内容服务-保存内容快照开始 workspaceId:{} operatorUserId:{} purpose:{} originName:{}",
                workspaceId, operatorUserId, purpose, originalName);
        WorkspaceEntity workspaceEntity = getActiveWorkspace(workspaceId);
        UserEntity userEntity = getAvailableUser(operatorUserId);
        validateContent(content, originalName, purpose);

        // 1. 生成内容快照编码和隔离后的对象存储路径。
        String contentCode = generateUniqueContentCode();
        String bucketName = objectStorageRepository.getBucketName();
        String safeContentName = normalizeContentName(originalName);
        String objectKey = buildObjectKey(workspaceEntity.getId(), purpose, contentCode, safeContentName);
        String suffix = extractSuffix(originalName);
        LocalDateTime now = LocalDateTime.now();
        String contentHash = DigestUtils.md5Hex(content);

        try {
            // 2. 先写对象存储，再登记仅供文档版本引用的内容元数据。
            objectStorageRepository.upload(objectKey, content, normalizeContentType(contentType));
            DocumentContentEntity contentEntity = DocumentContentEntity.builder()
                    .contentCode(contentCode)
                    .workspaceId(workspaceEntity.getId())
                    .creatorUserId(userEntity.getId())
                    .purpose(purpose)
                    .originName(originalName)
                    .suffix(suffix)
                    .contentType(normalizeContentType(contentType))
                    .contentSize((long) content.length)
                    .storageType(STORAGE_TYPE_MINIO)
                    .bucketName(bucketName)
                    .storagePath(objectKey)
                    .contentHash(contentHash)
                    .status(DocumentContentStatusVO.SUCCESS)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            DocumentContentEntity savedContent = contentRepository.save(contentEntity);
            log.info("文档内容服务-保存内容快照完成 contentId:{} contentCode:{} workspaceId:{} purpose:{}",
                    savedContent.getId(), savedContent.getContentCode(), savedContent.getWorkspaceId(), savedContent.getPurpose());
            return savedContent;
        } catch (Exception exception) {
            try {
                objectStorageRepository.delete(objectKey);
            } catch (Exception cleanupException) {
                log.warn("文档内容服务-保存失败后清理对象异常 contentCode:{} objectKey:{} error:{}",
                        contentCode, objectKey, cleanupException.getMessage(), cleanupException);
            }
            log.error("文档内容服务-保存内容快照异常 contentCode:{} workspaceId:{} operatorUserId:{} purpose:{} error:{}",
                    contentCode, workspaceEntity.getId(), userEntity.getId(), purpose, exception.getMessage(), exception);
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_STORE_FAILED);
        }
    }

    @Override
    public DocumentContentEntity getAvailableContent(Long workspaceId, Long contentId) {
        WorkspaceEntity workspaceEntity = getActiveWorkspace(workspaceId);
        log.info("文档内容服务-查询内容快照开始 workspaceId:{} contentId:{}", workspaceEntity.getId(), contentId);
        DocumentContentEntity content = getWorkspaceAvailableContent(workspaceEntity.getId(), contentId);
        log.info("文档内容服务-查询内容快照完成 workspaceId:{} contentId:{} contentCode:{}",
                workspaceEntity.getId(), content.getId(), content.getContentCode());
        return content;
    }

    @Override
    public byte[] readContent(Long workspaceId, Long contentId) {
        WorkspaceEntity workspaceEntity = getActiveWorkspace(workspaceId);
        log.info("文档内容服务-读取内容开始 workspaceId:{} contentId:{}", workspaceEntity.getId(), contentId);
        DocumentContentEntity content = getWorkspaceAvailableContent(workspaceEntity.getId(), contentId);
        byte[] contentBytes = objectStorageRepository.download(content.getStoragePath());
        log.info("文档内容服务-读取内容完成 workspaceId:{} contentId:{} contentSize:{}",
                workspaceEntity.getId(), content.getId(), contentBytes.length);
        return contentBytes;
    }

    @Override
    @Transactional
    public DocumentContentEntity deleteContent(Long workspaceId, Long contentId) {
        WorkspaceEntity workspaceEntity = getActiveWorkspace(workspaceId);
        log.info("文档内容服务-删除内容快照开始 workspaceId:{} contentId:{}", workspaceEntity.getId(), contentId);
        DocumentContentEntity content = getWorkspaceAvailableContent(workspaceEntity.getId(), contentId);

        DocumentContentEntity deletedContent = rebuildContent(
                content, DocumentContentStatusVO.DELETED, LocalDateTime.now());
        contentRepository.update(deletedContent);
        try {
            objectStorageRepository.delete(content.getStoragePath());
        } catch (Exception exception) {
            log.warn("文档内容服务-删除对象失败但已完成逻辑删除 contentCode:{} objectKey:{} error:{}",
                    content.getContentCode(), content.getStoragePath(), exception.getMessage(), exception);
        }
        log.info("文档内容服务-删除内容快照完成 workspaceId:{} contentId:{} contentCode:{}",
                workspaceEntity.getId(), deletedContent.getId(), deletedContent.getContentCode());
        return deletedContent;
    }

    private WorkspaceEntity getActiveWorkspace(Long workspaceId) {
        if (workspaceId == null || workspaceId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_WORKSPACE_ID);
        }
        WorkspaceEntity workspaceEntity = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(ResponseCode.WORKSPACE_NOT_FOUND));
        if (!workspaceEntity.isActive()) {
            throw new BusinessException(ResponseCode.WORKSPACE_STATUS_UNAVAILABLE);
        }
        return workspaceEntity;
    }

    private UserEntity getAvailableUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_USER_ID);
        }
        UserEntity userEntity = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!userEntity.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }
        return userEntity;
    }

    private DocumentContentEntity getWorkspaceAvailableContent(Long workspaceId, Long contentId) {
        if (contentId == null || contentId <= 0) {
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND);
        }
        DocumentContentEntity content = contentRepository.findById(contentId)
                .orElseThrow(() -> new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND));
        if (!workspaceId.equals(content.getWorkspaceId())
                || content.getStatus() == DocumentContentStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_NOT_FOUND);
        }
        return content;
    }

    private void validateContent(byte[] content, String originalName, DocumentContentPurposeVO purpose) {
        if (content == null || (content.length == 0 && purpose == DocumentContentPurposeVO.ORIGINAL)) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_CONTENT);
        }
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_DOCUMENT_CONTENT);
        }
    }

    private String generateUniqueContentCode() {
        for (int i = 0; i < 10; i++) {
            String contentCode = CONTENT_CODE_PREFIX + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12)
                    .toLowerCase(Locale.ROOT);
            if (contentRepository.findByContentCode(contentCode).isEmpty()) {
                return contentCode;
            }
        }
        throw new BusinessException(ResponseCode.DOCUMENT_CONTENT_STORE_FAILED);
    }

    // 按用途分目录，便于排查文档内容对象，同时保持 Workspace 级存储隔离。
    private String buildObjectKey(Long workspaceId, DocumentContentPurposeVO purpose, String contentCode,
                                  String contentName) {
        return "workspace/" + workspaceId + "/document-content/"
                + purpose.name().toLowerCase(Locale.ROOT) + "/" + contentCode + "_" + contentName;
    }

    private String normalizeContentName(String contentName) {
        if (contentName == null || contentName.isBlank()) {
            return "content";
        }
        String normalized = contentName.replace('\\', '_').replace('/', '_').trim();
        return normalized.replaceAll("\\s+", "_");
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType;
    }

    // 提取内容名称后缀，供解析器识别导入格式。
    private String extractSuffix(String contentName) {
        if (contentName == null) {
            return null;
        }
        int index = contentName.lastIndexOf('.');
        if (index < 0 || index == contentName.length() - 1) {
            return null;
        }
        return contentName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private DocumentContentEntity rebuildContent(DocumentContentEntity source, DocumentContentStatusVO status,
                                                 LocalDateTime updatedAt) {
        return DocumentContentEntity.builder()
                .id(source.getId())
                .contentCode(source.getContentCode())
                .workspaceId(source.getWorkspaceId())
                .creatorUserId(source.getCreatorUserId())
                .purpose(source.getPurpose())
                .originName(source.getOriginName())
                .suffix(source.getSuffix())
                .contentType(source.getContentType())
                .contentSize(source.getContentSize())
                .storageType(source.getStorageType())
                .bucketName(source.getBucketName())
                .storagePath(source.getStoragePath())
                .contentHash(source.getContentHash())
                .status(status)
                .createdAt(source.getCreatedAt())
                .updatedAt(updatedAt)
                .build();
    }
}

package com.hector.domain.file.service.impl;

import com.hector.domain.file.model.entity.FileEntity;
import com.hector.domain.file.model.valobj.FileStatusVO;
import com.hector.domain.file.repository.IFileRepository;
import com.hector.domain.file.repository.IFileStorageRepository;
import com.hector.domain.file.service.IFileService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
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
 * 文件领域服务实现。
 */
@Service
public class FileServiceImpl implements IFileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);
    private static final String FILE_CODE_PREFIX = "file_";
    private static final String STORAGE_TYPE_MINIO = "MINIO";

    @Resource
    private IFileRepository fileRepository;

    @Resource
    private IFileStorageRepository fileStorageRepository;

    @Resource
    private IUserRepository userRepository;

    @Override
    @Transactional
    public FileEntity uploadFile(Long operatorUserId, String originalFilename, String contentType, byte[] fileBytes) {
        log.info("文件领域服务-上传文件开始 operatorUserId:{} originalFilename:{}", operatorUserId, originalFilename);
        UserEntity userEntity = getAvailableUser(operatorUserId);
        validateFile(fileBytes, originalFilename);

        // file_ + 12 位 UUID
        String fileCode = generateUniqueFileCode();
        String bucketName = fileStorageRepository.getBucketName();
        String safeOriginalFilename = normalizeFilename(originalFilename);
        String objectKey = buildObjectKey(userEntity.getId(), fileCode, safeOriginalFilename);
        String fileSuffix = extractFileSuffix(originalFilename);
        LocalDateTime now = LocalDateTime.now();
        String fileHash = DigestUtils.md5Hex(fileBytes);

        try {
            fileStorageRepository.upload(objectKey, fileBytes, normalizeContentType(contentType));
            FileEntity fileEntity = FileEntity.builder()
                    .fileCode(fileCode)
                    .creatorUserId(userEntity.getId())
                    .fileName(originalFilename)
                    .originName(originalFilename)
                    .fileSuffix(fileSuffix)
                    .contentType(normalizeContentType(contentType))
                    .fileSize((long) fileBytes.length)
                    .storageType(STORAGE_TYPE_MINIO)
                    .bucketName(bucketName)
                    .storagePath(objectKey)
                    .fileHash(fileHash)
                    .status(FileStatusVO.SUCCESS)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            FileEntity savedFileEntity = fileRepository.save(fileEntity);
            log.info("文件领域服务-上传文件完成 fileId:{} fileCode:{} creatorUserId:{}",
                    savedFileEntity.getId(), savedFileEntity.getFileCode(), savedFileEntity.getCreatorUserId());
            return savedFileEntity;
        } catch (Exception exception) {
            try {
                fileStorageRepository.delete(objectKey);
            } catch (Exception cleanupException) {
                log.warn("文件领域服务-上传失败后清理对象失败 fileCode:{} objectKey:{} error:{}",
                        fileCode, objectKey, cleanupException.getMessage(), cleanupException);
            }
            log.error("文件领域服务-上传文件失败 fileCode:{} creatorUserId:{} error:{}",
                    fileCode, userEntity.getId(), exception.getMessage(), exception);
            throw new BusinessException(ResponseCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public List<FileEntity> listMyFiles(Long operatorUserId) {
        UserEntity userEntity = getAvailableUser(operatorUserId);
        log.info("文件领域服务-查询用户文件列表开始 userId:{}", userEntity.getId());
        List<FileEntity> fileEntities = fileRepository.listByCreatorUserId(userEntity.getId()).stream()
                .filter(fileEntity -> fileEntity.getStatus() != FileStatusVO.DELETED)
                .collect(Collectors.toList());
        log.info("文件领域服务-查询用户文件列表完成 userId:{} fileCount:{}", userEntity.getId(), fileEntities.size());
        return fileEntities;
    }

    @Override
    public FileEntity getFileDetail(Long operatorUserId, String fileCode) {
        UserEntity userEntity = getAvailableUser(operatorUserId);
        log.info("文件领域服务-查询文件详情开始 userId:{} fileCode:{}", userEntity.getId(), fileCode);
        FileEntity fileEntity = getOwnedVisibleFile(userEntity.getId(), fileCode);
        log.info("文件领域服务-查询文件详情完成 fileId:{} fileCode:{} creatorUserId:{}",
                fileEntity.getId(), fileEntity.getFileCode(), fileEntity.getCreatorUserId());
        return fileEntity;
    }

    @Override
    @Transactional
    public FileEntity deleteFile(Long operatorUserId, String fileCode) {
        UserEntity userEntity = getAvailableUser(operatorUserId);
        log.info("文件领域服务-删除文件开始 userId:{} fileCode:{}", userEntity.getId(), fileCode);
        FileEntity fileEntity = getOwnedVisibleFile(userEntity.getId(), fileCode);

        FileEntity deletedFileEntity = rebuildFile(fileEntity, FileStatusVO.DELETED, LocalDateTime.now());
        fileRepository.update(deletedFileEntity);
        try {
            fileStorageRepository.delete(fileEntity.getStoragePath());
        } catch (Exception exception) {
            log.warn("文件领域服务-删除对象失败但已完成逻辑删除 fileCode:{} objectKey:{} error:{}",
                    fileCode, fileEntity.getStoragePath(), exception.getMessage(), exception);
        }
        log.info("文件领域服务-删除文件完成 fileId:{} fileCode:{} creatorUserId:{}",
                deletedFileEntity.getId(), deletedFileEntity.getFileCode(), deletedFileEntity.getCreatorUserId());
        return deletedFileEntity;
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

    private FileEntity getOwnedVisibleFile(Long creatorUserId, String fileCode) {
        if (fileCode == null || fileCode.isBlank()) {
            throw new BusinessException(ResponseCode.FILE_NOT_FOUND);
        }
        FileEntity fileEntity = fileRepository.findByFileCode(fileCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.FILE_NOT_FOUND));
        if (!creatorUserId.equals(fileEntity.getCreatorUserId())
                || fileEntity.getStatus() == FileStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.FILE_NOT_FOUND);
        }
        return fileEntity;
    }

    private void validateFile(byte[] fileBytes, String originalFilename) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ResponseCode.EMPTY_FILE);
        }
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_FILE);
        }
    }

    private String generateUniqueFileCode() {
        for (int i = 0; i < 10; i++) {
            String fileCode = FILE_CODE_PREFIX + UUID.randomUUID().toString()
                    .replace("-", "")
                    .substring(0, 12)
                    .toLowerCase(Locale.ROOT);
            if (fileRepository.findByFileCode(fileCode).isEmpty()) {
                return fileCode;
            }
        }
        throw new BusinessException(ResponseCode.FILE_UPLOAD_FAILED);
    }

    // user/{userId}/{fileCode}_{originalFilename}
    private String buildObjectKey(Long userId, String fileCode, String originalFilename) {
        return "user/" + userId + "/" + fileCode + "_" + originalFilename;
    }

    private String normalizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        String normalized = filename.replace('\\', '_').replace('/', '_').trim();
        return normalized.replaceAll("\\s+", "_");
    }

    private String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "application/octet-stream";
        }
        return contentType;
    }

    // 提取文件后缀
    private String extractFileSuffix(String originalFilename) {
        if (originalFilename == null) {
            return null;
        }
        int index = originalFilename.lastIndexOf('.');
        if (index < 0 || index == originalFilename.length() - 1) {
            return null;
        }
        return originalFilename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private FileEntity rebuildFile(FileEntity source, FileStatusVO status, LocalDateTime updatedAt) {
        return FileEntity.builder()
                .id(source.getId())
                .fileCode(source.getFileCode())
                .creatorUserId(source.getCreatorUserId())
                .fileName(source.getFileName())
                .originName(source.getOriginName())
                .fileSuffix(source.getFileSuffix())
                .contentType(source.getContentType())
                .fileSize(source.getFileSize())
                .storageType(source.getStorageType())
                .bucketName(source.getBucketName())
                .storagePath(source.getStoragePath())
                .fileHash(source.getFileHash())
                .status(status)
                .createdAt(source.getCreatedAt())
                .updatedAt(updatedAt)
                .build();
    }
}

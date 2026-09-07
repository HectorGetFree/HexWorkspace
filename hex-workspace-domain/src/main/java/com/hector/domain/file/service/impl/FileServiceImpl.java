package com.hector.domain.file.service.impl;

import com.hector.domain.file.model.entity.FileEntity;
import com.hector.domain.file.model.valobj.FileStatusVO;
import com.hector.domain.file.model.valobj.FilePurposeVO;
import com.hector.domain.file.repository.IFileRepository;
import com.hector.domain.file.repository.IFileStorageRepository;
import com.hector.domain.file.service.IFileService;
import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.repository.ITenantRepository;
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

    @Resource
    private ITenantRepository tenantRepository;

    @Override
    @Transactional
    public FileEntity uploadFile(Long tenantId, Long operatorUserId, String originalFilename, String contentType, byte[] fileBytes) {
        return storeFile(tenantId, operatorUserId, originalFilename, contentType, fileBytes, FilePurposeVO.ORIGINAL);
    }

    @Override
    @Transactional
    public FileEntity storeDerivedFile(Long tenantId, Long operatorUserId, String filename, String contentType, byte[] fileBytes) {
        return storeFile(tenantId, operatorUserId, filename, contentType, fileBytes, FilePurposeVO.DERIVED);
    }

    /**
     * 统一管理对象写入与元数据落库；入口方法保留原始/派生语义，避免调用方用“上传”描述系统生成内容。
     */
    private FileEntity storeFile(Long tenantId, Long operatorUserId, String originalFilename, String contentType,
                                 byte[] fileBytes, FilePurposeVO purpose) {
        log.info("文件领域服务-保存文件开始 tenantId:{} operatorUserId:{} purpose:{} filename:{}",
                tenantId, operatorUserId, purpose, originalFilename);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        UserEntity userEntity = getAvailableUser(operatorUserId);
        validateFile(fileBytes, originalFilename);

        // file_ + 12 位 UUID
        String fileCode = generateUniqueFileCode();
        String bucketName = fileStorageRepository.getBucketName();
        String safeOriginalFilename = normalizeFilename(originalFilename);
        String objectKey = buildObjectKey(tenantEntity.getId(), purpose, fileCode, safeOriginalFilename);
        String fileSuffix = extractFileSuffix(originalFilename);
        LocalDateTime now = LocalDateTime.now();
        String fileHash = DigestUtils.md5Hex(fileBytes);

        try {
            fileStorageRepository.upload(objectKey, fileBytes, normalizeContentType(contentType));
            FileEntity fileEntity = FileEntity.builder()
                    .fileCode(fileCode)
                    .tenantId(tenantEntity.getId())
                    .creatorUserId(userEntity.getId())
                    .purpose(purpose)
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
            log.info("文件领域服务-保存文件完成 fileId:{} fileCode:{} tenantId:{} creatorUserId:{} purpose:{}",
                    savedFileEntity.getId(), savedFileEntity.getFileCode(), savedFileEntity.getTenantId(), savedFileEntity.getCreatorUserId(), savedFileEntity.getPurpose());
            return savedFileEntity;
        } catch (Exception exception) {
            try {
                fileStorageRepository.delete(objectKey);
            } catch (Exception cleanupException) {
                log.warn("文件领域服务-上传失败后清理对象失败 fileCode:{} objectKey:{} error:{}",
                        fileCode, objectKey, cleanupException.getMessage(), cleanupException);
            }
            log.error("文件领域服务-保存文件失败 fileCode:{} tenantId:{} creatorUserId:{} purpose:{} error:{}",
                    fileCode, tenantEntity.getId(), userEntity.getId(), purpose, exception.getMessage(), exception);
            throw new BusinessException(ResponseCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public FileEntity getAvailableFile(Long tenantId, Long fileId) {
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        log.info("文件领域服务-查询文件开始 tenantId:{} fileId:{}", tenantEntity.getId(), fileId);
        FileEntity fileEntity = getTenantAvailableFile(tenantEntity.getId(), fileId);
        log.info("文件领域服务-查询文件完成 tenantId:{} fileId:{} fileCode:{}",
                tenantEntity.getId(), fileEntity.getId(), fileEntity.getFileCode());
        return fileEntity;
    }

    @Override
    public byte[] readFileContent(Long tenantId, Long fileId) {
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        log.info("文件领域服务-读取文件内容开始 tenantId:{} fileId:{}", tenantEntity.getId(), fileId);
        FileEntity fileEntity = getTenantAvailableFile(tenantEntity.getId(), fileId);
        byte[] fileContent = fileStorageRepository.download(fileEntity.getStoragePath());
        log.info("文件领域服务-读取文件内容完成 tenantId:{} fileId:{} fileSize:{}",
                tenantEntity.getId(), fileEntity.getId(), fileContent.length);
        return fileContent;
    }

    @Override
    @Transactional
    public FileEntity deleteFile(Long tenantId, Long fileId) {
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        log.info("文件领域服务-删除文件开始 tenantId:{} fileId:{}", tenantEntity.getId(), fileId);
        FileEntity fileEntity = getTenantAvailableFile(tenantEntity.getId(), fileId);

        FileEntity deletedFileEntity = rebuildFile(fileEntity, FileStatusVO.DELETED, LocalDateTime.now());
        fileRepository.update(deletedFileEntity);
        try {
            fileStorageRepository.delete(fileEntity.getStoragePath());
        } catch (Exception exception) {
            log.warn("文件领域服务-删除对象失败但已完成逻辑删除 fileCode:{} objectKey:{} error:{}",
                    fileEntity.getFileCode(), fileEntity.getStoragePath(), exception.getMessage(), exception);
        }
        log.info("文件领域服务-删除文件完成 tenantId:{} fileId:{} fileCode:{}",
                tenantEntity.getId(), deletedFileEntity.getId(), deletedFileEntity.getFileCode());
        return deletedFileEntity;
    }

    private TenantEntity getActiveTenant(Long tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ResponseCode.EMPTY_TENANT_ID);
        }
        TenantEntity tenantEntity = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(ResponseCode.TENANT_NOT_FOUND));
        if (!tenantEntity.isActive()) {
            throw new BusinessException(ResponseCode.TENANT_STATUS_UNAVAILABLE);
        }
        return tenantEntity;
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

    private FileEntity getTenantAvailableFile(Long tenantId, Long fileId) {
        if (fileId == null || fileId <= 0) {
            throw new BusinessException(ResponseCode.FILE_NOT_FOUND);
        }
        FileEntity fileEntity = fileRepository.findById(fileId)
                .orElseThrow(() -> new BusinessException(ResponseCode.FILE_NOT_FOUND));
        if (!tenantId.equals(fileEntity.getTenantId())
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

    // 按用途分目录，方便人工排查对象且不改变 file 表作为统一资源索引的职责。
    private String buildObjectKey(Long tenantId, FilePurposeVO purpose, String fileCode, String originalFilename) {
        return "tenant/" + tenantId + "/file/" + purpose.name().toLowerCase(Locale.ROOT) + "/" + fileCode + "_" + originalFilename;
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
                .tenantId(source.getTenantId())
                .creatorUserId(source.getCreatorUserId())
                .purpose(source.getPurpose())
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

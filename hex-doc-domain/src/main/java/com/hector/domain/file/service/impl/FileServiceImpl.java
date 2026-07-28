package com.hector.domain.file.service.impl;

import com.hector.domain.file.model.entity.FileEntity;
import com.hector.domain.file.model.valobj.FileScopeVO;
import com.hector.domain.file.model.valobj.FileStatusVO;
import com.hector.domain.file.repository.IFileRepository;
import com.hector.domain.file.repository.IFileStorageRepository;
import com.hector.domain.file.service.IFileService;
import com.hector.domain.membership.model.entity.MembershipEntity;
import com.hector.domain.membership.repository.IMembershipRepository;
import com.hector.domain.tenant.model.entity.TenantEntity;
import com.hector.domain.tenant.repository.ITenantRepository;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Resource
    private IFileRepository fileRepository;

    @Resource
    private IFileStorageRepository fileStorageRepository;

    @Resource
    private ITenantRepository tenantRepository;

    @Resource
    private IMembershipRepository membershipRepository;

    @Override
    @Transactional
    public FileEntity uploadTenantFile(Long operatorUserId, Long tenantId, String originalFilename,
                                       String contentType, byte[] fileBytes) {
        log.info("文件领域服务-上传租户文件开始 operatorUserId:{} tenantId:{} originalFilename:{}",
                operatorUserId, tenantId, originalFilename);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        // 校验权限
        assertCanManageTenantFiles(tenantEntity, operatorUserId);
        validateFile(fileBytes, originalFilename);

        // file_ + 12 位 UUID
        String fileCode = generateUniqueFileCode();
        String bucketName = fileStorageRepository.getBucketName();
        String safeOriginalFilename = normalizeFilename(originalFilename);
        String objectKey = buildObjectKey(tenantEntity.getId(), fileCode, safeOriginalFilename);
        String fileSuffix = extractFileSuffix(originalFilename);
        LocalDateTime now = LocalDateTime.now();
        String fileHash = DigestUtils.md5Hex(fileBytes);

        try {
            fileStorageRepository.upload(objectKey, fileBytes, normalizeContentType(contentType));
            FileEntity fileEntity = FileEntity.builder()
                    .fileCode(fileCode)
                    .tenantId(tenantEntity.getId())
                    .bucketName(bucketName)
                    .scope(FileScopeVO.TENANT_PUBLIC)
                    .creatorUserId(operatorUserId)
                    .fileName(originalFilename)
                    .originName(originalFilename)
                    .fileSuffix(fileSuffix)
                    .contentType(normalizeContentType(contentType))
                    .fileSize((long) fileBytes.length)
                    .storageType(STORAGE_TYPE_MINIO)
                    .storagePath(objectKey)
                    .fileHash(fileHash)
                    .status(FileStatusVO.SUCCESS)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            FileEntity savedFileEntity = fileRepository.save(fileEntity);
            log.info("文件领域服务-上传租户文件完成 fileId:{} fileCode:{} tenantId:{}",
                    savedFileEntity.getId(), savedFileEntity.getFileCode(), savedFileEntity.getTenantId());
            return savedFileEntity;
        } catch (Exception exception) {
            try {
                fileStorageRepository.delete(objectKey);
            } catch (Exception cleanupException) {
                log.warn("文件领域服务-上传失败后清理对象失败 fileCode:{} objectKey:{} error:{}",
                        fileCode, objectKey, cleanupException.getMessage(), cleanupException);
            }
            log.error("文件领域服务-上传租户文件失败 fileCode:{} tenantId:{} error:{}",
                    fileCode, tenantId, exception.getMessage(), exception);
            throw new BusinessException(ResponseCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public List<FileEntity> listTenantFiles(Long operatorUserId, Long tenantId) {
        log.info("文件领域服务-查询租户文件列表开始 operatorUserId:{} tenantId:{}", operatorUserId, tenantId);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        assertCanViewTenantFiles(tenantEntity, operatorUserId);
        List<FileEntity> fileEntities = fileRepository.listByTenantId(tenantEntity.getId()).stream()
                .filter(fileEntity -> fileEntity.getStatus() != FileStatusVO.DELETED)
                .filter(fileEntity -> FileScopeVO.TENANT_PUBLIC == fileEntity.getScope())
                .collect(Collectors.toList());
        log.info("文件领域服务-查询租户文件列表完成 tenantId:{} fileCount:{}", tenantEntity.getId(), fileEntities.size());
        return fileEntities;
    }

    @Override
    public FileEntity getTenantFileDetail(Long operatorUserId, Long tenantId, String fileCode) {
        log.info("文件领域服务-查询租户文件详情开始 operatorUserId:{} tenantId:{} fileCode:{}",
                operatorUserId, tenantId, fileCode);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        assertCanViewTenantFiles(tenantEntity, operatorUserId);
        FileEntity fileEntity = getVisibleFile(tenantEntity.getId(), fileCode);
        log.info("文件领域服务-查询租户文件详情完成 fileId:{} fileCode:{} tenantId:{}",
                fileEntity.getId(), fileEntity.getFileCode(), fileEntity.getTenantId());
        return fileEntity;
    }

    @Override
    @Transactional
    public FileEntity deleteTenantFile(Long operatorUserId, Long tenantId, String fileCode) {
        log.info("文件领域服务-删除租户文件开始 operatorUserId:{} tenantId:{} fileCode:{}",
                operatorUserId, tenantId, fileCode);
        TenantEntity tenantEntity = getActiveTenant(tenantId);
        assertCanManageTenantFiles(tenantEntity, operatorUserId);
        FileEntity fileEntity = getVisibleFile(tenantEntity.getId(), fileCode);

        FileEntity deletedFileEntity = rebuildFile(fileEntity, FileStatusVO.DELETED, LocalDateTime.now());
        fileRepository.update(deletedFileEntity);
        try {
            fileStorageRepository.delete(fileEntity.getStoragePath());
        } catch (Exception exception) {
            log.warn("文件领域服务-删除对象失败但已完成逻辑删除 fileCode:{} objectKey:{} error:{}",
                    fileCode, fileEntity.getStoragePath(), exception.getMessage(), exception);
        }
        log.info("文件领域服务-删除租户文件完成 fileId:{} fileCode:{} tenantId:{}",
                deletedFileEntity.getId(), deletedFileEntity.getFileCode(), deletedFileEntity.getTenantId());
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

    private void assertCanViewTenantFiles(TenantEntity tenantEntity, Long operatorUserId) {
        if (tenantEntity.getOwnerUserId().equals(operatorUserId)) {
            return;
        }
        MembershipEntity membershipEntity = getActiveMembership(tenantEntity.getId(), operatorUserId);
        if (membershipEntity == null) {
            throw new BusinessException(ResponseCode.FILE_PERMISSION_DENIED);
        }
    }

    private void assertCanManageTenantFiles(TenantEntity tenantEntity, Long operatorUserId) {
        if (tenantEntity.getOwnerUserId().equals(operatorUserId)) {
            return;
        }
        MembershipEntity membershipEntity = getActiveMembership(tenantEntity.getId(), operatorUserId);
        if (membershipEntity == null || !membershipEntity.isAdmin()) {
            throw new BusinessException(ResponseCode.FILE_PERMISSION_DENIED);
        }
    }

    private MembershipEntity getActiveMembership(Long tenantId, Long userId) {
        Optional<MembershipEntity> membershipEntity = membershipRepository.findByUserIdAndTenantId(userId, tenantId);
        return membershipEntity.filter(MembershipEntity::isActive).orElse(null);
    }

    private FileEntity getVisibleFile(Long tenantId, String fileCode) {
        if (fileCode == null || fileCode.isBlank()) {
            throw new BusinessException(ResponseCode.FILE_NOT_FOUND);
        }
        FileEntity fileEntity = fileRepository.findByFileCode(fileCode)
                .orElseThrow(() -> new BusinessException(ResponseCode.FILE_NOT_FOUND));
        if (!tenantId.equals(fileEntity.getTenantId())
                || fileEntity.getStatus() == FileStatusVO.DELETED
                || fileEntity.getScope() != FileScopeVO.TENANT_PUBLIC) {
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

    // tenant/{tenantId}/{yyyyMMdd}/{fileCode}_{originalFilename}
    private String buildObjectKey(Long tenantId, String fileCode, String originalFilename) {
        String datePath = LocalDateTime.now().format(DATE_FORMATTER);
        return "tenant/" + tenantId + "/" + datePath + "/" + fileCode + "_" + originalFilename;
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
                .bucketName(source.getBucketName())
                .scope(source.getScope())
                .creatorUserId(source.getCreatorUserId())
                .fileName(source.getFileName())
                .originName(source.getOriginName())
                .fileSuffix(source.getFileSuffix())
                .contentType(source.getContentType())
                .fileSize(source.getFileSize())
                .storageType(source.getStorageType())
                .storagePath(source.getStoragePath())
                .fileHash(source.getFileHash())
                .status(status)
                .createdAt(source.getCreatedAt())
                .updatedAt(updatedAt)
                .build();
    }
}

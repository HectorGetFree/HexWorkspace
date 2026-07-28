package com.hector.infrastructure.repository;

import com.hector.domain.file.model.entity.FileEntity;
import com.hector.domain.file.model.valobj.FileStatusVO;
import com.hector.domain.file.repository.IFileRepository;
import com.hector.infrastructure.dao.IFileDao;
import com.hector.infrastructure.dao.po.File;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 文件仓储实现。
 */
@Repository
public class FileRepository implements IFileRepository {

    private static final Logger log = LoggerFactory.getLogger(FileRepository.class);

    @Resource
    private IFileDao fileDao;

    @Override
    public FileEntity save(FileEntity fileEntity) {
        log.info("文件仓储-保存文件开始 fileCode:{} creatorUserId:{} fileName:{}",
                fileEntity.getFileCode(), fileEntity.getCreatorUserId(), fileEntity.getFileName());
        File file = toPo(fileEntity);
        fileDao.insert(file);
        FileEntity savedFileEntity = toEntity(file);
        log.info("文件仓储-保存文件完成 fileId:{} fileCode:{}", savedFileEntity.getId(), savedFileEntity.getFileCode());
        return savedFileEntity;
    }

    @Override
    public void update(FileEntity fileEntity) {
        log.info("文件仓储-更新文件开始 fileId:{} fileCode:{} status:{}", fileEntity.getId(), fileEntity.getFileCode(), fileEntity.getStatus());
        fileDao.update(toPo(fileEntity));
        log.info("文件仓储-更新文件完成 fileId:{} fileCode:{}", fileEntity.getId(), fileEntity.getFileCode());
    }

    @Override
    public Optional<FileEntity> findById(Long fileId) {
        log.info("文件仓储-按ID查询开始 fileId:{}", fileId);
        File file = fileDao.queryFileById(fileId);
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(toEntity(file));
    }

    @Override
    public Optional<FileEntity> findByFileCode(String fileCode) {
        log.info("文件仓储-按编码查询开始 fileCode:{}", fileCode);
        File file = fileDao.queryFileByFileCode(fileCode);
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(toEntity(file));
    }

    @Override
    public List<FileEntity> listByCreatorUserId(Long creatorUserId) {
        log.info("文件仓储-按创建者查询列表开始 creatorUserId:{}", creatorUserId);
        List<FileEntity> fileEntities = fileDao.queryFilesByCreatorUserId(creatorUserId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
        log.info("文件仓储-按创建者查询列表完成 creatorUserId:{} fileCount:{}", creatorUserId, fileEntities.size());
        return fileEntities;
    }

    private File toPo(FileEntity fileEntity) {
        return File.builder()
                .id(fileEntity.getId())
                .fileCode(fileEntity.getFileCode())
                .creatorUserId(fileEntity.getCreatorUserId())
                .fileName(fileEntity.getFileName())
                .originName(fileEntity.getOriginName())
                .fileSuffix(fileEntity.getFileSuffix())
                .contentType(fileEntity.getContentType())
                .fileSize(fileEntity.getFileSize())
                .storageType(fileEntity.getStorageType())
                .bucketName(fileEntity.getBucketName())
                .storagePath(fileEntity.getStoragePath())
                .fileHash(fileEntity.getFileHash())
                .status(fileEntity.getStatus().name())
                .createdAt(fileEntity.getCreatedAt())
                .updatedAt(fileEntity.getUpdatedAt())
                .build();
    }

    private FileEntity toEntity(File file) {
        return FileEntity.builder()
                .id(file.getId())
                .fileCode(file.getFileCode())
                .creatorUserId(file.getCreatorUserId())
                .fileName(file.getFileName())
                .originName(file.getOriginName())
                .fileSuffix(file.getFileSuffix())
                .contentType(file.getContentType())
                .fileSize(file.getFileSize())
                .storageType(file.getStorageType())
                .bucketName(file.getBucketName())
                .storagePath(file.getStoragePath())
                .fileHash(file.getFileHash())
                .status(FileStatusVO.valueOf(file.getStatus()))
                .createdAt(file.getCreatedAt())
                .updatedAt(file.getUpdatedAt())
                .build();
    }
}

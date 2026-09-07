package com.hector.domain.file.repository;

import com.hector.domain.file.model.entity.FileEntity;

import java.util.List;
import java.util.Optional;

/**
 * 文件仓储接口。
 */
public interface IFileRepository {

    /**
     * 保存文件。
     */
    FileEntity save(FileEntity fileEntity);

    /**
     * 更新文件。
     */
    void update(FileEntity fileEntity);

    /**
     * 按主键查询文件。
     */
    Optional<FileEntity> findById(Long fileId);

    /**
     * 按文件编码查询文件。
     */
    Optional<FileEntity> findByFileCode(String fileCode);

    /**
     * 按租户ID查询文件列表。
     */
    List<FileEntity> listByTenantId(Long tenantId);
}

package com.hector.domain.file.service;

import com.hector.domain.file.model.entity.FileEntity;

import java.util.List;

/**
 * 文件领域服务接口。
 */
public interface IFileService {

    /**
     * 上传租户共享文件。
     */
    FileEntity uploadTenantFile(Long operatorUserId, Long tenantId, String originalFilename,
                                String contentType, byte[] fileBytes);

    /**
     * 查询租户文件列表。
     */
    List<FileEntity> listTenantFiles(Long operatorUserId, Long tenantId);

    /**
     * 查询租户文件详情。
     */
    FileEntity getTenantFileDetail(Long operatorUserId, Long tenantId, String fileCode);

    /**
     * 删除租户文件。
     */
    FileEntity deleteTenantFile(Long operatorUserId, Long tenantId, String fileCode);
}

package com.hector.domain.file.service;

import com.hector.domain.file.model.entity.FileEntity;

/**
 * 文件领域服务接口。
 */
public interface IFileService {

    /**
     * 在指定租户中存储文件。
     * 调用方负责完成所属业务资源（例如文档）的成员与权限校验。
     */
    FileEntity uploadFile(Long tenantId, Long operatorUserId, String originalFilename, String contentType, byte[] fileBytes);

    /**
     * 查询指定租户下可用的文件元数据。
     */
    FileEntity getAvailableFile(Long tenantId, Long fileId);

    /**
     * 删除指定租户下的文件。
     * 调用方必须先确认文件未被任何业务资源引用。
     */
    FileEntity deleteFile(Long tenantId, Long fileId);
}

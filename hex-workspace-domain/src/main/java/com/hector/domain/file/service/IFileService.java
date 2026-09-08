package com.hector.domain.file.service;

import com.hector.domain.file.model.entity.FileEntity;

/**
 * 文件领域服务接口。
 */
public interface IFileService {

    /**
     * 保存用户上传的原始文件。
     * 调用方负责完成所属业务资源（例如文档）的成员与权限校验。
     */
    FileEntity uploadFile(Long workspaceId, Long operatorUserId, String originalFilename, String contentType, byte[] fileBytes);

    /**
     * 保存系统生成的派生文件。
     * operatorUserId 表示触发该业务动作的用户，用于审计；它不表示用户直接上传了这个对象。
     */
    FileEntity storeDerivedFile(Long workspaceId, Long operatorUserId, String filename, String contentType, byte[] fileBytes);

    /**
     * 查询指定Workspace下可用的文件元数据。
     */
    FileEntity getAvailableFile(Long workspaceId, Long fileId);

    /**
     * 读取Workspace 内可用文件的二进制内容。
     * 调用方负责完成所属业务资源的访问授权。
     */
    byte[] readFileContent(Long workspaceId, Long fileId);

    /**
     * 删除指定Workspace下的文件。
     * 调用方必须先确认文件未被任何业务资源引用。
     */
    FileEntity deleteFile(Long workspaceId, Long fileId);
}

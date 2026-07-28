package com.hector.domain.file.service;

import com.hector.domain.file.model.entity.FileEntity;

import java.util.List;

/**
 * 文件领域服务接口。
 */
public interface IFileService {

    /**
     * 上传文件。
     */
    FileEntity uploadFile(Long operatorUserId, String originalFilename, String contentType, byte[] fileBytes);

    /**
     * 查询当前用户文件列表。
     */
    List<FileEntity> listMyFiles(Long operatorUserId);

    /**
     * 查询文件详情。
     */
    FileEntity getFileDetail(Long operatorUserId, String fileCode);

    /**
     * 删除文件。
     */
    FileEntity deleteFile(Long operatorUserId, String fileCode);
}

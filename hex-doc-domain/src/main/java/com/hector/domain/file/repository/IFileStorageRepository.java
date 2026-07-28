package com.hector.domain.file.repository;

/**
 * 文件对象存储仓储接口。
 */
public interface IFileStorageRepository {

    /**
     * 获取默认桶名。
     */
    String getBucketName();

    /**
     * 上传文件对象。
     */
    void upload(String objectKey, byte[] content, String contentType);

    /**
     * 删除文件对象。
     */
    void delete(String objectKey);
}

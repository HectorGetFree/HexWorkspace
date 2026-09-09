package com.hector.domain.document.repository;

/**
 * 文档内容对象存储端口。
 */
public interface IDocumentObjectStorageRepository {

    /**
     * 获取默认桶名。
     */
    String getBucketName();

    /**
     * 写入文档内容对象。
     */
    void upload(String objectKey, byte[] content, String contentType);

    /**
     * 读取文档内容对象。
     */
    byte[] download(String objectKey);

    /**
     * 删除文档内容对象。
     */
    void delete(String objectKey);
}

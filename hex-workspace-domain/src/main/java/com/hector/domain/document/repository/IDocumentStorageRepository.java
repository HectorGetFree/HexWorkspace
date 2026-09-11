package com.hector.domain.document.repository;

/**
 * 文档内容对象存储端口。
 * 当前由 MinIO 实现；领域层只依赖对象读写能力。
 */
public interface IDocumentStorageRepository {

    /** 获取默认对象存储桶名称。 */
    String getBucketName();

    /** 写入文档内容对象。 */
    void upload(String objectKey, byte[] content, String contentType);

    /** 读取文档内容对象。 */
    byte[] download(String objectKey);

    /** 删除文档内容对象。 */
    void delete(String objectKey);
}

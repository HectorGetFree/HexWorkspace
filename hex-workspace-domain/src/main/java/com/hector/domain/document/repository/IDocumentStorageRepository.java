package com.hector.domain.document.repository;

import com.hector.domain.document.model.valobj.DocumentStorageObjectVO;

import java.io.InputStream;
import java.util.List;

/**
 * 文档内容对象存储端口。
 * 当前由 MinIO 实现；领域层只依赖对象读写能力。
 */
public interface IDocumentStorageRepository {

    /** 获取默认对象存储桶名称。 */
    String getBucketName();

    /** 写入文档内容对象。 */
    void upload(String objectKey, byte[] content, String contentType);

    /** 以流式方式写入文档内容对象。 */
    void upload(String objectKey, InputStream content, long contentSize, String contentType);

    /** 在对象存储服务端复制对象，并写入指定 MIME 类型。 */
    void copy(String sourceObjectKey, String targetObjectKey, String contentType);

    /** 读取文档内容对象。 */
    byte[] download(String objectKey);

    /** 删除文档内容对象。 */
    void delete(String objectKey);

    /** 查询指定前缀下的对象元数据。 */
    List<DocumentStorageObjectVO> listByPrefix(String prefix);
}

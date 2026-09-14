package com.hector.infrastructure.repository;

import com.hector.domain.document.model.valobj.DocumentStorageObjectVO;
import com.hector.domain.document.repository.IDocumentStorageRepository;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.Directive;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** MinIO 文档对象存储实现。 */
@Repository
public class DocumentStorageRepository implements IDocumentStorageRepository {
    @Resource
    private MinioClient minioClient;

    @Value("${minio.sdk.config.bucket}")
    private String bucketName;

    @Override
    public String getBucketName() {
        return bucketName;
    }

    @Override
    public void upload(String objectKey, byte[] content, String contentType) {
        upload(objectKey, new ByteArrayInputStream(content), content.length, contentType);
    }

    @Override
    public void upload(String objectKey, InputStream content, long contentSize, String contentType) {
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(content, contentSize, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 文档写入失败", exception);
        }
    }

    @Override
    public void copy(String sourceObjectKey, String targetObjectKey, String contentType) {
        try {
            ensureBucketExists();
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(bucketName)
                    .object(targetObjectKey)
                    .source(CopySource.builder().bucket(bucketName).object(sourceObjectKey).build())
                    .metadataDirective(Directive.REPLACE)
                    .headers(Map.of("Content-Type", contentType))
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 文档对象复制失败", exception);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        try {
            ensureBucketExists();
            try (InputStream input = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build())) {
                return input.readAllBytes();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 文档读取失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            ensureBucketExists();
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 文档删除失败", exception);
        }
    }

    @Override
    public List<DocumentStorageObjectVO> listByPrefix(String prefix) {
        try {
            ensureBucketExists();
            List<DocumentStorageObjectVO> objects = new ArrayList<>();
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                Item item = result.get();
                if (item.isDir()) {
                    continue;
                }
                LocalDateTime lastModifiedAt = item.lastModified() == null
                        ? null : item.lastModified().toLocalDateTime();
                objects.add(DocumentStorageObjectVO.builder()
                        .objectKey(item.objectName())
                        .lastModifiedAt(lastModifiedAt)
                        .build());
            }
            return objects;
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 文档对象列表查询失败", exception);
        }
    }

    private void ensureBucketExists() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }
}

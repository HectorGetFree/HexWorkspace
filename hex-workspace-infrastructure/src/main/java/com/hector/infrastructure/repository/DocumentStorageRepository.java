package com.hector.infrastructure.repository;

import com.hector.domain.document.repository.IDocumentStorageRepository;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** MinIO 文档对象存储实现。 */
@Repository
public class DocumentStorageRepository implements IDocumentStorageRepository {
    @Resource private MinioClient minioClient;
    @Value("${minio.sdk.config.bucket}") private String bucketName;

    @Override public String getBucketName() { return bucketName; }

    @Override
    public void upload(String objectKey, byte[] content, String contentType) {
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder().bucket(bucketName).object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1).contentType(contentType).build());
        } catch (Exception exception) { throw new IllegalStateException("MinIO 文档写入失败", exception); }
    }

    @Override
    public byte[] download(String objectKey) {
        try {
            ensureBucketExists();
            try (InputStream input = minioClient.getObject(GetObjectArgs.builder().bucket(bucketName).object(objectKey).build())) {
                return input.readAllBytes();
            }
        } catch (Exception exception) { throw new IllegalStateException("MinIO 文档读取失败", exception); }
    }

    @Override
    public void delete(String objectKey) {
        try {
            ensureBucketExists();
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(objectKey).build());
        } catch (Exception exception) { throw new IllegalStateException("MinIO 文档删除失败", exception); }
    }

    private void ensureBucketExists() throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }
}

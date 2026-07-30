package com.hector.infrastructure.repository;

import com.hector.domain.file.repository.IFileStorageRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 文件存储仓储实现。
 */
@Repository
public class FileStorageRepository implements IFileStorageRepository {

    private static final Logger log = LoggerFactory.getLogger(FileStorageRepository.class);

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
        try {
            ensureBucketExists();
            log.info("文件存储仓储-MinIO上传开始 bucketName:{} objectKey:{}", bucketName, objectKey);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType(contentType)
                    .build());
            log.info("文件存储仓储-MinIO上传完成 bucketName:{} objectKey:{}", bucketName, objectKey);
        } catch (Exception exception) {
            log.error("文件存储仓储-MinIO上传失败 bucketName:{} objectKey:{} error:{}",
                    bucketName, objectKey, exception.getMessage(), exception);
            throw new RuntimeException(exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            ensureBucketExists();
            log.info("文件存储仓储-MinIO删除开始 bucketName:{} objectKey:{}", bucketName, objectKey);
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build());
            log.info("文件存储仓储-MinIO删除完成 bucketName:{} objectKey:{}", bucketName, objectKey);
        } catch (Exception exception) {
            log.error("文件存储仓储-MinIO删除失败 bucketName:{} objectKey:{} error:{}",
                    bucketName, objectKey, exception.getMessage(), exception);
            throw new RuntimeException(exception);
        }
    }

    @Override
    public byte[] download(String objectKey) {
        try {
            ensureBucketExists();
            log.info("文件存储仓储-MinIO读取开始 bucketName:{} objectKey:{}", bucketName, objectKey);
            try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(objectKey)
                    .build())) {
                byte[] content = inputStream.readAllBytes();
                log.info("文件存储仓储-MinIO读取完成 bucketName:{} objectKey:{} fileSize:{}",
                        bucketName, objectKey, content.length);
                return content;
            }
        } catch (Exception exception) {
            log.error("文件存储仓储-MinIO读取失败 bucketName:{} objectKey:{} error:{}",
                    bucketName, objectKey, exception.getMessage(), exception);
            throw new RuntimeException(exception);
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
        }
    }
}

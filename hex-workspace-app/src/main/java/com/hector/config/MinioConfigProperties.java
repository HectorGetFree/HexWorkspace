package com.hector.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 连接配置。
 */
@Data
@ConfigurationProperties(prefix = "minio.sdk.config", ignoreInvalidFields = true)
public class MinioConfigProperties {

    /** 访问地址。 */
    private String endpoint;

    /** AccessKey。 */
    private String accessKey;

    /** SecretKey。 */
    private String secretKey;

    /** 默认桶名。 */
    private String bucket;
}

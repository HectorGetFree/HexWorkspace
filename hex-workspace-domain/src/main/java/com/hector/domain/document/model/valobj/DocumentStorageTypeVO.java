package com.hector.domain.document.model.valobj;

/** 文档正文的对象存储类型。 */
public enum DocumentStorageTypeVO {
    /** MinIO 兼容 S3 的对象存储。 */
    MINIO(0);

    /** 持久化类型码，禁止依赖枚举声明顺序。 */
    private final int code;

    DocumentStorageTypeVO(int code) { this.code = code; }

    public int getCode() { return code; }

    public static DocumentStorageTypeVO fromCode(Integer code) {
        for (DocumentStorageTypeVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知文档存储类型码: " + code);
    }
}

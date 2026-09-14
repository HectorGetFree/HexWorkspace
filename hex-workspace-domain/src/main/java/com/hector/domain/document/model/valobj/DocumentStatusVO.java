package com.hector.domain.document.model.valobj;

/**
 * 文档生命周期状态。
 */
public enum DocumentStatusVO {
    /** 已落库、等待写入 MinIO 的新文档。 */
    CREATING(0),
    /** 已落库、等待写入 MinIO 的内容更新。 */
    WRITING(1),
    /** 可正常查看、编辑和参与知识处理。 */
    ACTIVE(2),
    /** 已逻辑删除，不可再对外访问。 */
    DELETED(3),
    /** 文档首次内容写入失败且重试次数已耗尽。 */
    FAILED(4);

    /** 持久化状态码，禁止依赖枚举声明顺序。 */
    private final int code;

    DocumentStatusVO(int code) { this.code = code; }

    public int getCode() { return code; }

    public static DocumentStatusVO fromCode(Integer code) {
        for (DocumentStatusVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知文档状态码: " + code);
    }
}

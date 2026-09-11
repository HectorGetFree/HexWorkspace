package com.hector.domain.document.model.valobj;

/**
 * 文档内容来源类型。
 */
public enum DocumentTypeVO {
    /** 由本地上传转化的云文档。 */
    LOCAL_UPLOADED(0),
    /** 由平台 Markdown 编辑器创建。 */
    PLATFORM_CREATED(1);

    /** 持久化类型码，禁止依赖枚举声明顺序。 */
    private final int code;

    DocumentTypeVO(int code) { this.code = code; }

    public int getCode() { return code; }

    public static DocumentTypeVO fromCode(Integer code) {
        for (DocumentTypeVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知文档类型码: " + code);
    }
}

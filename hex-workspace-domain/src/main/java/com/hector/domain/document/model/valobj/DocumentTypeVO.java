package com.hector.domain.document.model.valobj;

/**
 * 文档内容来源类型。
 */
public enum DocumentTypeVO {
    /** 由本地上传转化的云文档。 */
    LOCAL_UPLOADED,
    /** 由平台 Markdown 编辑器创建。 */
    PLATFORM_CREATED
}

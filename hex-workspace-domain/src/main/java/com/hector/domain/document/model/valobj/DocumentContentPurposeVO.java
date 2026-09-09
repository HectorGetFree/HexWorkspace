package com.hector.domain.document.model.valobj;

/**
 * 文档内容快照用途。
 * ORIGINAL 是用户导入的原始内容，DERIVED 是解析流程生成的派生内容。
 */
public enum DocumentContentPurposeVO {

    /** 用户从本地导入并作为文档初始版本保存的内容。 */
    ORIGINAL,

    /** 由平台编辑或解析流程生成的内容。 */
    DERIVED
}

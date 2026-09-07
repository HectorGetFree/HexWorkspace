package com.hector.domain.document.model.valobj;

/**
 * 文档生命周期状态。
 */
public enum DocumentStatusVO {
    /** 可正常查看、编辑和参与知识处理。 */
    ACTIVE,
    /** 被归档，不再参与日常编辑。 */
    ARCHIVED,
    /** 已逻辑删除，不可再对外访问。 */
    DELETED
}

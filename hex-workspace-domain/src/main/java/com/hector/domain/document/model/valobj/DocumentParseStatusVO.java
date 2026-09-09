package com.hector.domain.document.model.valobj;

/**
 * 文档版本的文本解析状态。
 */
public enum DocumentParseStatusVO {
    /** 当前版本尚未请求知识解析。 */
    NOT_REQUESTED,
    /** 等待解析。 */
    PENDING,
    /** 正在解析。 */
    PROCESSING,
    /** 解析成功，可作为知识索引输入。 */
    SUCCESS,
    /** 解析失败，可由异步任务重试。 */
    FAILED
}

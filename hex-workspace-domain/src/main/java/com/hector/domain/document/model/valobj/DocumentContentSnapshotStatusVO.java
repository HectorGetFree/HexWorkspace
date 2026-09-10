package com.hector.domain.document.model.valobj;

/**
 * 文档内容快照状态。
 */
public enum DocumentContentSnapshotStatusVO {

    /** 快照元数据已创建，等待对象存储写入。 */
    PENDING,

    /** 内容快照可正常读取。 */
    SUCCESS,

    /** 内容快照写入失败。 */
    FAILED,

    /** 内容快照已删除。 */
    DELETED
}

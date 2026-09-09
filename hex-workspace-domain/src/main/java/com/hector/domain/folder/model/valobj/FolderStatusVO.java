package com.hector.domain.folder.model.valobj;

/** 文件夹生命周期状态。 */
public enum FolderStatusVO {
    /** 可在目录树中正常使用。 */
    ACTIVE,
    /** 已进入逻辑删除状态。 */
    DELETED
}

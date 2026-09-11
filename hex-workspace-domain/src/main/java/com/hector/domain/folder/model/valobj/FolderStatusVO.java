package com.hector.domain.folder.model.valobj;

/** 文件夹生命周期状态。 */
public enum FolderStatusVO {
    /** 可在目录树中正常使用。 */
    ACTIVE(0),
    /** 已进入逻辑删除状态。 */
    DELETED(1);

    /** 持久化状态码，禁止依赖枚举声明顺序。 */
    private final int code;

    FolderStatusVO(int code) { this.code = code; }

    public int getCode() { return code; }

    public static FolderStatusVO fromCode(Integer code) {
        for (FolderStatusVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知文件夹状态码: " + code);
    }
}

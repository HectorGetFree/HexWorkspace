package com.hector.domain.file.model.valobj;

/**
 * 文件状态值对象。
 * 用于表达文件在平台中的生命周期阶段。
 */
public enum FileStatusVO {

    /** 文件已上传完成，等待后续处理。 */
    UPLOADED,

    /** 文件已成功入库并可正常使用。 */
    SUCCESS,

    /** 文件处理失败。 */
    FAILED,

    /** 文件已删除。 */
    DELETED
}

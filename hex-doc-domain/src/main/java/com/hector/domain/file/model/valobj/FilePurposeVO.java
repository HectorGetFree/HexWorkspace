package com.hector.domain.file.model.valobj;

/**
 * 文件资源用途。
 * ORIGINAL 是用户提供的业务输入，DERIVED 是系统基于原始文件生成的可复用产物；二者共用文件生命周期管理，但不能混淆创建语义。
 */
public enum FilePurposeVO {
    ORIGINAL,
    DERIVED
}

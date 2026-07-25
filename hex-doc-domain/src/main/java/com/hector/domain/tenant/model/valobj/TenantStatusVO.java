package com.hector.domain.tenant.model.valobj;

/**
 * 租户状态值对象。
 * 用于表达租户空间是否还能继续承载成员、文档和知识库等业务流转。
 */
public enum TenantStatusVO {
    /** 租户正常服务中。 */
    ACTIVE,
    /** 租户被暂停，通常禁止继续产生新的业务写入。 */
    SUSPENDED
}

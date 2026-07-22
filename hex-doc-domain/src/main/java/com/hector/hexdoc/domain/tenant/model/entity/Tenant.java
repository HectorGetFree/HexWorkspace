package com.hector.hexdoc.domain.tenant.model.entity;

import com.hector.hexdoc.domain.tenant.model.valobj.TenantStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户实体。
 * 表示平台中的一个独立业务空间，用于隔离文档、知识库和成员关系等核心业务数据。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {

    /** 租户主键ID。 */
    private Long id;

    /** 租户唯一编码，适合用于路径、缓存Key和跨系统标识。 */
    private String tenantCode;

    /** 租户展示名称。 */
    private String tenantName;

    /** 租户当前状态。 */
    private TenantStatusVO status;

    /** 租户拥有者用户ID。 */
    private Long ownerUserId;

    /** 租户描述信息。 */
    private String description;

    /** 租户创建时间。 */
    private LocalDateTime createdAt;

    /** 租户最后更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 暂停租户。
     * 暂停后通常只保留查询能力，禁止继续发起新的写操作。
     */
    public void suspend() {
        this.status = TenantStatusVO.SUSPENDED;
    }

    /**
     * 激活租户。
     * 用于恢复租户正常业务流转能力。
     */
    public void activate() {
        this.status = TenantStatusVO.ACTIVE;
    }

    /**
     * 转移租户拥有者。
     * 用于租户管理员交接等场景。
     */
    public void transferOwner(Long newOwnerUserId) {
        this.ownerUserId = newOwnerUserId;
    }

    /**
     * 判断租户当前是否允许继续承载正常业务。
     */
    public boolean isActive() {
        return TenantStatusVO.ACTIVE == this.status;
    }
}

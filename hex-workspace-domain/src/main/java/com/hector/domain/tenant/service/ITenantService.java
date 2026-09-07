package com.hector.domain.tenant.service;

import com.hector.domain.tenant.model.entity.TenantEntity;

import java.util.List;

/**
 * 租户领域服务接口。
 * 负责租户实体自身的创建、查询和基础管理能力。
 */
public interface ITenantService {

    /**
     * 创建租户。
     */
    TenantEntity createTenant(String tenantName, Long ownerUserId, String description);

    /**
     * 按租户ID查询租户。
     */
    TenantEntity getTenantById(Long tenantId);

    /**
     * 按租户编码查询租户。
     */
    TenantEntity getTenantByCode(String tenantCode);

    /**
     * 按拥有者用户ID查询租户列表。
     */
    List<TenantEntity> listTenantsByOwnerUserId(Long ownerUserId);
}

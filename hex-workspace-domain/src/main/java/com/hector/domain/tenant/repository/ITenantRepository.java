package com.hector.domain.tenant.repository;

import com.hector.domain.tenant.model.entity.TenantEntity;

import java.util.List;
import java.util.Optional;

/**
 * 租户仓储接口。
 */
public interface ITenantRepository {

    /**
     * 保存租户。
     */
    TenantEntity save(TenantEntity tenantEntity);

    /**
     * 按主键查询租户。
     */
    Optional<TenantEntity> findById(Long tenantId);

    /**
     * 按租户编码查询租户。
     */
    Optional<TenantEntity> findByTenantCode(String tenantCode);

    /**
     * 按拥有者用户ID查询租户列表。
     */
    List<TenantEntity> listByOwnerUserId(Long ownerUserId);
}

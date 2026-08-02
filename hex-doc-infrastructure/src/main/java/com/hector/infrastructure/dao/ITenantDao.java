package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.Tenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户 DAO。
 */
@Mapper
public interface ITenantDao {

    /**
     * 新增租户。
     */
    int insert(Tenant tenant);

    /**
     * 按主键查询租户。
     */
    Tenant queryTenantById(@Param("id") Long id);

    /**
     * 按租户编码查询租户。
     */
    Tenant queryTenantByTenantCode(@Param("tenantCode") String tenantCode);

    /**
     * 按拥有者用户ID查询租户列表。
     */
    List<Tenant> queryTenantsByOwnerUserId(@Param("ownerUserId") Long ownerUserId);
}

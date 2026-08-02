package com.hector.infrastructure.dao;

import com.hector.infrastructure.dao.po.Membership;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 成员关系 DAO。
 */
@Mapper
public interface IMembershipDao {

    /**
     * 新增成员关系。
     */
    int insert(Membership membership);

    /**
     * 更新成员关系。
     */
    int update(Membership membership);

    /**
     * 按用户ID和租户ID查询成员关系。
     */
    Membership queryMembershipByUserIdAndTenantId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 按租户ID查询成员列表。
     */
    List<Membership> queryMembershipsByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 按用户ID查询成员关系列表。
     */
    List<Membership> queryMembershipsByUserId(@Param("userId") Long userId);
}

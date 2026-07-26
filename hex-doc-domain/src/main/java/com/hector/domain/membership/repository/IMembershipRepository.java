package com.hector.domain.membership.repository;

import com.hector.domain.membership.model.entity.MembershipEntity;

import java.util.List;
import java.util.Optional;

/**
 * 成员关系仓储接口。
 */
public interface IMembershipRepository {

    /**
     * 保存成员关系。
     */
    MembershipEntity save(MembershipEntity membershipEntity);

    /**
     * 更新成员关系。
     */
    void update(MembershipEntity membershipEntity);

    /**
     * 按主键查询成员关系。
     */
    Optional<MembershipEntity> findById(Long membershipId);

    /**
     * 按用户ID和租户ID查询成员关系。
     */
    Optional<MembershipEntity> findByUserIdAndTenantId(Long userId, Long tenantId);

    /**
     * 按租户ID查询成员列表。
     */
    List<MembershipEntity> listByTenantId(Long tenantId);

    /**
     * 按用户ID查询成员关系列表。
     */
    List<MembershipEntity> listByUserId(Long userId);
}

package com.hector.domain.membership.service;

import com.hector.domain.membership.model.entity.MembershipEntity;
import java.util.List;

/**
 * 成员关系领域服务接口。
 * 负责租户成员关系的创建、查询、角色变更和关系流转。
 */
public interface IMembershipService {

    /**
     * 初始化租户拥有者成员关系。
     */
    MembershipEntity initOwnerMember(Long operatorUserId, Long tenantId);

    /**
     * 向租户添加成员。
     */
    MembershipEntity addMember(Long operatorUserId, Long tenantId, Long targetUserId);

    /**
     * 查询租户成员列表。
     */
    List<MembershipEntity> listMembersByTenantId(Long operatorUserId, Long tenantId);

    /**
     * 查询当前用户的成员关系列表。
     */
    List<MembershipEntity> listMembershipsByUserId(Long userId);

    /**
     * 移除成员。
     */
    MembershipEntity removeMember(Long operatorUserId, Long tenantId, Long targetUserId);
}

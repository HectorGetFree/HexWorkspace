package com.hector.hexdoc.domain.membership.model.valobj;

/**
 * 成员角色值对象。
 * 用于表达用户在租户内部扮演的是管理者还是普通成员。
 */
public enum MembershipRoleVO {
    /** 具备租户内成员管理和关键资源管理能力。 */
    ADMIN,
    /** 具备租户内日常协作和资源使用能力。 */
    MEMBER
}

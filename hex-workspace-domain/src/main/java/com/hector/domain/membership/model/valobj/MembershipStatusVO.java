package com.hector.domain.membership.model.valobj;

/**
 * 成员关系状态值对象。
 * 用于表达用户与租户之间关系的生命周期，而不是简单的数据库字段值。
 */
public enum MembershipStatusVO {
    /** 已发送邀请，但成员尚未正式加入。 */
    INVITED,
    /** 成员关系已正式生效。 */
    ACTIVE,
    /** 成员关系被临时冻结。 */
    SUSPENDED,
    /** 成员主动退出租户。 */
    LEFT,
    /** 成员被管理员移出租户。 */
    REMOVED
}

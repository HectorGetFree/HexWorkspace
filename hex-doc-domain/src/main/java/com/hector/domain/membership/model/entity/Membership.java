package com.hector.domain.membership.model.entity;

import com.hector.domain.membership.model.valobj.MembershipRoleVO;
import com.hector.domain.membership.model.valobj.MembershipStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户成员关系实体。
 * 用于表示某个用户在某个租户中的成员身份、角色以及关系生命周期。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Membership {

    /** 成员关系主键ID。 */
    private Long id;

    /** 关联的用户ID。 */
    private Long userId;

    /** 关联的租户ID。 */
    private Long tenantId;

    /** 用户在该租户中的角色。 */
    private MembershipRoleVO role;

    /** 成员关系当前状态。 */
    private MembershipStatusVO status;

    /** 用户正式加入租户的时间。 */
    private LocalDateTime joinedAt;

    /** 向用户发出加入租户邀请的时间。 */
    private LocalDateTime invitedAt;

    /** 成员关系创建时间。 */
    private LocalDateTime createdAt;

    /** 成员关系最后更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 接受邀请并正式加入租户。
     * 1. 将成员关系切换为 ACTIVE。
     * 2. 记录正式加入时间，便于后续审计和成员生命周期分析。
     */
    public void activate(LocalDateTime joinedAt) {
        this.status = MembershipStatusVO.ACTIVE;
        this.joinedAt = joinedAt;
    }

    /**
     * 冻结成员关系。
     * 冻结后成员保留历史关系，但暂时不能继续访问租户资源。
     */
    public void suspend() {
        this.status = MembershipStatusVO.SUSPENDED;
    }

    /**
     * 成员主动退出租户。
     * 该动作前需要在应用层校验租户内是否仍保留至少一个 ACTIVE 的 ADMIN。
     */
    public void leave() {
        this.status = MembershipStatusVO.LEFT;
    }

    /**
     * 成员被管理员移出。
     * 该动作前同样需要在应用层校验关键管理员约束。
     */
    public void remove() {
        this.status = MembershipStatusVO.REMOVED;
    }

    /**
     * 调整成员角色。
     * 用于租户内的管理员授权或降权场景。
     */
    public void changeRole(MembershipRoleVO newRole) {
        this.role = newRole;
    }

    /**
     * 判断当前成员关系是否处于有效状态。
     */
    public boolean isActive() {
        return MembershipStatusVO.ACTIVE == this.status;
    }

    /**
     * 判断当前成员是否为租户管理员。
     */
    public boolean isAdmin() {
        return MembershipRoleVO.ADMIN == this.role;
    }
}

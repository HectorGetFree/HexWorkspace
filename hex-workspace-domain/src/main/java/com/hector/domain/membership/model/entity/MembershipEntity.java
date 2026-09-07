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
public class MembershipEntity {

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

    public void activate(LocalDateTime joinedAt) {
        this.status = MembershipStatusVO.ACTIVE;
        this.joinedAt = joinedAt;
    }

    public void suspend() {
        this.status = MembershipStatusVO.SUSPENDED;
    }

    public void leave() {
        this.status = MembershipStatusVO.LEFT;
    }

    public void remove() {
        this.status = MembershipStatusVO.REMOVED;
    }

    public void changeRole(MembershipRoleVO newRole) {
        this.role = newRole;
    }

    public boolean isActive() {
        return MembershipStatusVO.ACTIVE == this.status;
    }

    public boolean isAdmin() {
        return MembershipRoleVO.ADMIN == this.role;
    }
}

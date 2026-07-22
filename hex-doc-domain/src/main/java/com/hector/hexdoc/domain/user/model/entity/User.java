package com.hector.hexdoc.domain.user.model.entity;

import com.hector.hexdoc.domain.user.model.valobj.UserStatusVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户实体。
 * 表示平台中的全局身份主体，负责承载登录认证、账号状态流转等用户域核心规则。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /** 用户主键ID。 */
    private Long id;

    /** 全局唯一用户名，用于登录识别。 */
    private String username;

    /** 密码哈希值，不保存明文密码。 */
    private String passwordHash;

    /** 用户账号当前状态。 */
    private UserStatusVO status;

    /** 用户创建时间。 */
    private LocalDateTime createdAt;

    /** 用户最后更新时间。 */
    private LocalDateTime updatedAt;

    /**
     * 锁定账号。
     * 适用于账号存在风险或需要人工冻结的场景。
     */
    public void lock() {
        this.status = UserStatusVO.LOCKED;
    }

    /**
     * 禁用账号。
     * 禁用后用户不再允许继续登录系统。
     */
    public void disable() {
        this.status = UserStatusVO.DISABLED;
    }

    /**
     * 激活账号。
     * 用于恢复锁定或禁用后的账号可用性。
     */
    public void activate() {
        this.status = UserStatusVO.ACTIVE;
    }

    /**
     * 判断账号当前是否处于可用状态。
     */
    public boolean isActive() {
        return UserStatusVO.ACTIVE == this.status;
    }
}

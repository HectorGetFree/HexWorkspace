package com.hector.domain.user.model.valobj;

/**
 * 用户状态值对象。
 * 用于表达用户账号在业务上的可用性，而不是通用技术枚举。
 */
public enum UserStatusVO {
    /** 账号正常可用，允许登录和发起业务操作。 */
    ACTIVE,
    /** 账号因安全原因被锁定，通常需要管理员介入恢复。 */
    LOCKED,
    /** 账号被禁用，不再允许继续访问系统。 */
    DISABLED
}

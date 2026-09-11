package com.hector.domain.user.model.valobj;

/**
 * 用户状态值对象。
 * 用于表达用户账号在业务上的可用性，而不是通用技术枚举。
 */
public enum UserStatusVO {
    /** 账号正常可用，允许登录和发起业务操作。 */
    ACTIVE(0),
    /** 账号被禁用，不再允许继续访问系统。 */
    DISABLED(1);

    /** 持久化状态码，禁止依赖枚举声明顺序。 */
    private final int code;

    UserStatusVO(int code) { this.code = code; }

    public int getCode() { return code; }

    public static UserStatusVO fromCode(Integer code) {
        for (UserStatusVO value : values()) if (value.code == code) return value;
        throw new IllegalArgumentException("未知用户状态码: " + code);
    }
}

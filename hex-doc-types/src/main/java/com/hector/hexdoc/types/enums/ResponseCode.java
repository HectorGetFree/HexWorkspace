package com.hector.hexdoc.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 统一响应码枚举。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public enum ResponseCode {

    SUCCESS("0000", "success"),
    SYSTEM_ERROR("9999", "系统繁忙，请稍后重试"),
    ILLEGAL_PARAMETER("U400", "请求参数不合法"),
    AUTH_ERROR("U401", "登录状态已失效，请重新登录"),
    FORBIDDEN("U403", "当前操作不允许"),
    USER_NOT_FOUND("U404", "用户不存在"),
    USERNAME_DUPLICATE("U409", "用户名已存在"),
    USERNAME_OR_PASSWORD_ERROR("U401", "用户名或密码错误"),
    USER_STATUS_LOGIN_ERROR("U403", "账号当前不可登录"),
    USER_STATUS_UNAVAILABLE("U403", "账号当前不可用"),
    TENANT_NOT_FOUND("T404", "租户不存在"),
    TENANT_CODE_DUPLICATE("T409", "租户编码已存在"),
    EMPTY_USERNAME("U400", "用户名不能为空"),
    EMPTY_PASSWORD("U400", "密码不能为空"),
    EMPTY_TOKEN("U400", "访问令牌不能为空"),
    EMPTY_REQUEST("U400", "请求参数不能为空"),
    EMPTY_TENANT_CODE("T400", "租户编码不能为空"),
    EMPTY_TENANT_NAME("T400", "租户名称不能为空"),
    EMPTY_OWNER_USER_ID("T400", "租户拥有者不能为空"),
    ;

    private String code;
    private String info;
}

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
    TENANT_STATUS_UNAVAILABLE("T403", "租户当前不可用"),
    MEMBERSHIP_NOT_FOUND("M404", "成员关系不存在"),
    MEMBERSHIP_ALREADY_EXISTS("M409", "成员关系已存在"),
    MEMBERSHIP_OPERATOR_NOT_MEMBER("M403", "当前用户不是该租户成员"),
    MEMBERSHIP_OPERATOR_PERMISSION_DENIED("M403", "当前用户没有成员管理权限"),
    MEMBERSHIP_OWNER_CANNOT_REMOVE("M403", "租户拥有者不允许被移除"),
    FILE_NOT_FOUND("F404", "文件不存在"),
    FILE_PERMISSION_DENIED("F403", "当前用户没有文件操作权限"),
    FILE_UPLOAD_FAILED("F500", "文件上传失败"),
    FILE_DELETE_FAILED("F500", "文件删除失败"),
    EMPTY_FILE("F400", "文件不能为空"),
    EMPTY_USERNAME("U400", "用户名不能为空"),
    EMPTY_PASSWORD("U400", "密码不能为空"),
    EMPTY_TOKEN("U400", "访问令牌不能为空"),
    EMPTY_REQUEST("U400", "请求参数不能为空"),
    EMPTY_TENANT_CODE("T400", "租户编码不能为空"),
    EMPTY_TENANT_NAME("T400", "租户名称不能为空"),
    EMPTY_OWNER_USER_ID("T400", "租户拥有者不能为空"),
    EMPTY_TENANT_ID("T400", "租户ID不能为空"),
    EMPTY_USER_ID("U400", "用户ID不能为空"),
    ;

    private String code;
    private String info;
}

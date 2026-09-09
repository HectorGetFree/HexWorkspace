package com.hector.hexworkspace.types.enums;

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
    WORKSPACE_NOT_FOUND("W404", "Workspace不存在"),
    WORKSPACE_CODE_DUPLICATE("W409", "Workspace编码已存在"),
    WORKSPACE_STATUS_UNAVAILABLE("W403", "Workspace当前不可用"),
    WORKSPACE_OWNER_REQUIRED("W403", "仅 Workspace 所有者可执行此操作"),
    FOLDER_NOT_FOUND("R404", "文件夹不存在"),
    EMPTY_FOLDER_NAME("R400", "文件夹名称不能为空"),
    FOLDER_NAME_DUPLICATE("R409", "同级目录已存在同名文件夹"),
    FOLDER_MOVE_CYCLE("R400", "文件夹不能移动到自身或子目录"),
    ROOT_FOLDER_IMMUTABLE("R403", "根目录不能重命名、移动或删除"),
    FILE_NOT_FOUND("F404", "文件不存在"),
    FILE_PERMISSION_DENIED("F403", "当前用户没有文件操作权限"),
    FILE_UPLOAD_FAILED("F500", "文件上传失败"),
    FILE_DELETE_FAILED("F500", "文件删除失败"),
    EMPTY_FILE("F400", "文件不能为空"),
    DOCUMENT_NOT_FOUND("D404", "文档不存在"),
    DOCUMENT_STATUS_UNAVAILABLE("D403", "文档当前不可用"),
    DOCUMENT_PARSE_FAILED("D500", "文档解析失败"),
    DOCUMENT_TYPE_UNSUPPORTED("D400", "暂不支持该文档类型"),
    DOCUMENT_EDIT_UNSUPPORTED("D400", "当前文档格式不支持在线编辑"),
    DOCUMENT_NAME_DUPLICATE("D409", "同级目录已存在同名文档"),
    EMPTY_DOCUMENT_TITLE("D400", "文档标题不能为空"),
    EMPTY_USERNAME("U400", "用户名不能为空"),
    EMPTY_PASSWORD("U400", "密码不能为空"),
    EMPTY_TOKEN("U400", "访问令牌不能为空"),
    EMPTY_REQUEST("U400", "请求参数不能为空"),
    EMPTY_WORKSPACE_CODE("W400", "Workspace编码不能为空"),
    EMPTY_OWNER_USER_ID("T400", "Workspace拥有者不能为空"),
    EMPTY_WORKSPACE_NAME("W400", "Workspace名称不能为空"),
    EMPTY_WORKSPACE_ID("T400", "Workspace ID不能为空"),
    EMPTY_USER_ID("U400", "用户ID不能为空"),
    ;

    private String code;
    private String info;
}

package com.hector.trigger.http.security;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 当前登录用户主体。
 * 用于将已认证用户的关键身份信息放入 Spring Security 上下文。
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class LoginUserPrincipal {

    /** 用户主键ID。 */
    private Long userId;

    /** 用户名。 */
    private String username;

    /** 账号状态。 */
    private String status;
}

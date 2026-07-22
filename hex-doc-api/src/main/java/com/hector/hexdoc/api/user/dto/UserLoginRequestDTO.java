package com.hector.hexdoc.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户登录请求对象。
 * 用于承载用户登录时提交的身份凭证。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginRequestDTO implements Serializable {

    /** 用户名。 */
    private String username;

    /** 登录密码明文。 */
    private String password;
}

package com.hector.hexdoc.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户登录响应对象。
 * 用于返回登录成功后的访问令牌以及用户基础信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLoginResponseDTO implements Serializable {

    /** 访问令牌。 */
    private String token;

    /** 令牌类型。 */
    private String tokenType;

    /** 用户主键ID。 */
    private Long userId;

    /** 用户名。 */
    private String username;

    /** 账号状态。 */
    private String status;
}

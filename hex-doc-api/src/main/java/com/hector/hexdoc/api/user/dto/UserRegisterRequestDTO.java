package com.hector.hexdoc.api.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户注册请求对象。
 * 用于承载注册用户所需的最小信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRegisterRequestDTO implements Serializable {

    /** 全局唯一用户名。 */
    private String username;

    /** 用户注册密码明文，后续由服务侧负责加密。 */
    private String password;
}

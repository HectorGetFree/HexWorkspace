package com.hector.trigger.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户登出请求对象。
 * 第一版用于显式传递当前访问令牌，便于后续扩展黑名单或会话失效逻辑。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserLogoutRequestDTO implements Serializable {

    /** 当前登录令牌。 */
    private String token;
}

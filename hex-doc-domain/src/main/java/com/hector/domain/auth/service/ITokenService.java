package com.hector.domain.auth.service;

/**
 * 令牌服务接口。
 * 负责访问令牌的创建与解析，供登录流程与 Spring Security 过滤器复用。
 */
public interface ITokenService {

    /**
     * 创建访问令牌。
     */
    String createToken(Long userId, String username);

    /**
     * 从访问令牌中解析用户ID。
     */
    Long parseUserId(String token);
}

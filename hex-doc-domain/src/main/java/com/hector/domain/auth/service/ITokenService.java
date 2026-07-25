package com.hector.domain.auth.service;

/**
 * 令牌服务接口。
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

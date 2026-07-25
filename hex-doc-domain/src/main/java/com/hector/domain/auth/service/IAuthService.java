package com.hector.domain.auth.service;

import com.hector.domain.auth.model.entity.AuthSessionEntity;
import com.hector.domain.user.model.entity.UserEntity;

/**
 * 认证领域服务接口。
 * 负责登录态相关的认证和鉴权能力。
 */
public interface IAuthService {

    /**
     * 用户登录。
     */
    AuthSessionEntity login(String username, String rawPassword);

    /**
     * 用户登出。
     */
    void logout(String token);

    /**
     * 根据令牌获取当前用户。
     */
    UserEntity getCurrentUser(String token);
}

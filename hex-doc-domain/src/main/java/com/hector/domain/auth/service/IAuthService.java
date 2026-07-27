package com.hector.domain.auth.service;

import com.hector.domain.auth.model.entity.AuthSessionEntity;

/**
 * 认证领域服务接口。
 * 负责登录态相关的认证能力。
 */
public interface IAuthService {

    /**
     * 用户登录。
     */
    AuthSessionEntity login(String username, String rawPassword);
}

package com.hector.domain.auth.repository;

import java.time.Instant;

/** 已登出登录会话的撤销记录端口。 */
public interface ITokenRevocationRepository {

    /** 将会话撤销记录保留至其 refreshToken 原本过期时间。 */
    void revokeSession(String sessionId, Instant expiresAt);

    /** 判断会话是否已被撤销。 */
    boolean isSessionRevoked(String sessionId);
}

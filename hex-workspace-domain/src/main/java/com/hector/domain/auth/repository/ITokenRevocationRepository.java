package com.hector.domain.auth.repository;

import java.time.Instant;

/** 已登出访问令牌的撤销记录端口。 */
public interface ITokenRevocationRepository {

    /** 将令牌撤销记录保留至其原本过期时间。 */
    void revoke(String tokenId, Instant expiresAt);

    /** 判断令牌是否已被撤销。 */
    boolean isRevoked(String tokenId);
}

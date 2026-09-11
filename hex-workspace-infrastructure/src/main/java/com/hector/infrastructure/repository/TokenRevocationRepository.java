package com.hector.infrastructure.repository;

import com.hector.domain.auth.repository.ITokenRevocationRepository;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

/** 使用 Redis 保存已登出令牌；记录随 JWT 原过期时间自动失效。 */
@Repository
public class TokenRevocationRepository implements ITokenRevocationRepository {

    private static final String KEY_PREFIX = "auth:revoked:";

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void revoke(String tokenId, Instant expiresAt) {
        long remainingMillis = Duration.between(Instant.now(), expiresAt).toMillis();
        if (remainingMillis <= 0) return;
        redissonClient.getBucket(KEY_PREFIX + tokenId).set(Boolean.TRUE, Duration.ofMillis(remainingMillis));
    }

    @Override
    public boolean isRevoked(String tokenId) {
        return redissonClient.getBucket(KEY_PREFIX + tokenId).isExists();
    }
}

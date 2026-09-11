package com.hector.infrastructure.repository;

import com.hector.domain.auth.repository.ITokenRevocationRepository;
import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

/** 使用 Redis 保存已登出会话；记录随 refreshToken 原过期时间自动失效。 */
@Repository
public class TokenRevocationRepository implements ITokenRevocationRepository {

    private static final String KEY_PREFIX = "auth:revoked:session:";

    @Resource
    private RedissonClient redissonClient;

    @Override
    public void revokeSession(String sessionId, Instant expiresAt) {
        long remainingMillis = Duration.between(Instant.now(), expiresAt).toMillis();
        if (remainingMillis <= 0) return;
        redissonClient.getBucket(KEY_PREFIX + sessionId).set(Boolean.TRUE, Duration.ofMillis(remainingMillis));
    }

    @Override
    public boolean isSessionRevoked(String sessionId) {
        return redissonClient.getBucket(KEY_PREFIX + sessionId).isExists();
    }
}

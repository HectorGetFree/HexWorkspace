package com.hector.infrastructure.auth.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.hector.domain.auth.service.ITokenService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 令牌服务实现。
 */
@Service
public class TokenServiceImpl implements ITokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenServiceImpl.class);

    @Value("${hexdoc.jwt.secret:hex-doc-jwt-secret-key}")
    private String secret;

    @Value("${hexdoc.jwt.expire-hours:24}")
    private long expireHours;

    @Override
    public String createToken(Long userId, String username) {
        log.info("令牌服务-创建Token开始 userId:{} username:{}", userId, username);
        Instant now = Instant.now();
        Instant expireAt = now.plus(expireHours, ChronoUnit.HOURS);
        return JWT.create()
                .withSubject(String.valueOf(userId)) // JWT 的标准主题字段，用它表示唯一身份
                .withClaim("username", username) // 塞业务字段
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expireAt))
                .sign(Algorithm.HMAC256(secret)); // 使用相应相应算法对 token 签名
    }

    @Override
    public Long parseUserId(String token) {
        try {
            log.info("令牌服务-解析Token开始");
            DecodedJWT decodedJWT = JWT.require(Algorithm.HMAC256(secret))
                    .build()
                    .verify(token);
            return Long.valueOf(decodedJWT.getSubject());
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            log.error("令牌服务-解析Token异常", exception);
            throw new BusinessException(ResponseCode.AUTH_ERROR);
        }
    }
}

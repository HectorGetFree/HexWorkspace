package com.hector.domain.auth.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.hector.domain.auth.service.ITokenService;
import com.hector.domain.auth.model.valobj.AccessTokenClaimsVO;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 令牌服务实现。
 */
@Service
public class TokenServiceImpl implements ITokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenServiceImpl.class);

    @Value("${hexworkspace.jwt.secret:hex-workspace-jwt-secret-key}")
    private String secret;

    @Value("${hexworkspace.jwt.expire-hours:24}")
    private long expireHours;

    @Override
    public String createToken(Long userId, String username) {
        log.info("令牌服务-创建Token开始 userId:{} username:{}", userId, username);
        Instant now = Instant.now();
        Instant expireAt = now.plus(expireHours, ChronoUnit.HOURS);
        return JWT.create()
                .withSubject(String.valueOf(userId)) // JWT 的标准主题字段，用它表示唯一身份
                .withClaim("username", username) // 塞业务字段
                .withJWTId(UUID.randomUUID().toString()) // 单令牌撤销使用的唯一标识
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(expireAt))
                .sign(Algorithm.HMAC256(secret)); // 使用相应相应算法对 token 签名
    }

    @Override
    public AccessTokenClaimsVO parseToken(String token) {
        try {
            log.info("令牌服务-解析Token开始");
            DecodedJWT decodedJWT = JWT.require(Algorithm.HMAC256(secret))
                    .build()
                    .verify(token);
            String tokenId = decodedJWT.getId();
            if (tokenId == null || tokenId.isBlank() || decodedJWT.getExpiresAt() == null) {
                throw new BusinessException(ResponseCode.AUTH_ERROR);
            }
            return new AccessTokenClaimsVO(Long.valueOf(decodedJWT.getSubject()), tokenId,
                    decodedJWT.getExpiresAt().toInstant());
        } catch (JWTVerificationException | IllegalArgumentException exception) {
            log.error("令牌服务-解析Token异常", exception);
            throw new BusinessException(ResponseCode.AUTH_ERROR);
        }
    }
}

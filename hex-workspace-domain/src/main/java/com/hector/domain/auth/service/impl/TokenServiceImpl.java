package com.hector.domain.auth.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.hector.domain.auth.model.valobj.AuthTokenClaimsVO;
import com.hector.domain.auth.model.valobj.AuthTokenPairVO;
import com.hector.domain.auth.model.valobj.AuthTokenTypeVO;
import com.hector.domain.auth.service.ITokenService;
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

    @Value("${hexworkspace.jwt.access-expire-minutes:15}")
    private long accessExpireMinutes;

    @Value("${hexworkspace.jwt.refresh-expire-days:30}")
    private long refreshExpireDays;

    @Override
    public AuthTokenPairVO createTokenPair(Long userId, String username) {
        log.info("令牌服务-创建令牌对开始 userId:{} username:{}", userId, username);
        Instant now = Instant.now();
        String sessionId = UUID.randomUUID().toString();
        Instant sessionExpiresAt = now.plus(refreshExpireDays, ChronoUnit.DAYS);
        return new AuthTokenPairVO(
                createToken(userId, username, sessionId, AuthTokenTypeVO.ACCESS,
                        now.plus(accessExpireMinutes, ChronoUnit.MINUTES), sessionExpiresAt),
                createToken(userId, username, sessionId, AuthTokenTypeVO.REFRESH, sessionExpiresAt, sessionExpiresAt));
    }

    @Override
    public String createAccessToken(Long userId, String username, String sessionId, Instant sessionExpiresAt) {
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plus(accessExpireMinutes, ChronoUnit.MINUTES);
        if (accessExpiresAt.isAfter(sessionExpiresAt)) accessExpiresAt = sessionExpiresAt;
        return createToken(userId, username, sessionId, AuthTokenTypeVO.ACCESS, accessExpiresAt, sessionExpiresAt);
    }

    private String createToken(Long userId, String username, String sessionId, AuthTokenTypeVO tokenType,
                               Instant expiresAt, Instant sessionExpiresAt) {
        return JWT.create()
                .withSubject(String.valueOf(userId))
                .withClaim("username", username)
                .withClaim("token_type", tokenType.name())
                .withClaim("session_id", sessionId)
                .withClaim("session_expires_at", sessionExpiresAt.getEpochSecond())
                .withJWTId(UUID.randomUUID().toString())
                .withIssuedAt(Date.from(Instant.now()))
                .withExpiresAt(Date.from(expiresAt))
                .sign(Algorithm.HMAC256(secret));
    }

    @Override
    public AuthTokenClaimsVO parseAccessToken(String token) {
        return parseToken(token, AuthTokenTypeVO.ACCESS);
    }

    @Override
    public AuthTokenClaimsVO parseRefreshToken(String token) {
        return parseToken(token, AuthTokenTypeVO.REFRESH);
    }

    private AuthTokenClaimsVO parseToken(String token, AuthTokenTypeVO expectedType) {
        try {
            log.info("令牌服务-解析Token开始");
            DecodedJWT decodedJWT = JWT.require(Algorithm.HMAC256(secret))
                    .build()
                    .verify(token);
            String tokenId = decodedJWT.getId();
            String sessionId = decodedJWT.getClaim("session_id").asString();
            Long sessionExpiresAt = decodedJWT.getClaim("session_expires_at").asLong();
            AuthTokenTypeVO tokenType = AuthTokenTypeVO.valueOf(decodedJWT.getClaim("token_type").asString());
            if (tokenId == null || tokenId.isBlank() || sessionId == null || sessionId.isBlank()
                    || sessionExpiresAt == null || tokenType != expectedType || decodedJWT.getExpiresAt() == null) {
                throw new BusinessException(ResponseCode.AUTH_ERROR);
            }
            return new AuthTokenClaimsVO(Long.valueOf(decodedJWT.getSubject()), tokenId, sessionId,
                    decodedJWT.getExpiresAt().toInstant(), Instant.ofEpochSecond(sessionExpiresAt), tokenType);
        } catch (JWTVerificationException | IllegalArgumentException | NullPointerException exception) {
            log.error("令牌服务-解析Token异常", exception);
            throw new BusinessException(ResponseCode.AUTH_ERROR);
        }
    }
}

package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.model.entity.AuthSessionEntity;
import com.hector.domain.auth.service.IAuthService;
import com.hector.domain.auth.service.IPasswordService;
import com.hector.domain.auth.service.ITokenService;
import com.hector.domain.auth.model.valobj.AuthTokenClaimsVO;
import com.hector.domain.auth.model.valobj.AuthTokenPairVO;
import com.hector.domain.auth.repository.ITokenRevocationRepository;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.hexworkspace.types.enums.ResponseCode;
import com.hector.hexworkspace.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 认证领域服务实现。
 * 负责登录校验、访问令牌签发及当前令牌撤销。
 */
@Service
public class AuthServiceImpl implements IAuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    @Resource
    private IUserRepository userRepository;

    @Resource
    private IPasswordService passwordService;

    @Resource
    private ITokenService tokenService;

    @Resource
    private ITokenRevocationRepository tokenRevocationRepository;

    @Override
    public AuthSessionEntity login(String username, String rawPassword) {
        log.info("认证领域服务-用户登录开始 username:{}", username);
        String normalizedUsername = normalizeUsername(username);
        String normalizedPassword = normalizePassword(rawPassword);

        UserEntity userEntity = userRepository.findByUsername(normalizedUsername)
                .orElseThrow(() -> new BusinessException(ResponseCode.USERNAME_OR_PASSWORD_ERROR));

        if (!userEntity.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_LOGIN_ERROR);
        }
        if (!passwordService.matches(normalizedPassword, userEntity.getPasswordHash())) {
            throw new BusinessException(ResponseCode.USERNAME_OR_PASSWORD_ERROR);
        }

        AuthTokenPairVO tokenPair = createTokenPair(userEntity);
        AuthSessionEntity authSessionEntity = AuthSessionEntity.builder()
                .user(userEntity)
                .token(tokenPair.accessToken())
                .refreshToken(tokenPair.refreshToken())
                .build();
        log.info("认证领域服务-用户登录完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
        return authSessionEntity;
    }

    @Override
    public void logout(String token) {
        AuthTokenClaimsVO claims = tokenService.parseAccessToken(token);
        revokeSession(claims);
    }

    @Override
    public void logoutByRefreshToken(String refreshToken) {
        revokeSession(tokenService.parseRefreshToken(refreshToken));
    }

    @Override
    public AuthSessionEntity refresh(String refreshToken) {
        AuthTokenClaimsVO claims = tokenService.parseRefreshToken(refreshToken);
        if (tokenRevocationRepository.isSessionRevoked(claims.sessionId())) {
            throw new BusinessException(ResponseCode.AUTH_ERROR);
        }
        UserEntity user = userRepository.findById(claims.userId())
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!user.isActive()) throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        return AuthSessionEntity.builder().user(user)
                .token(tokenService.createAccessToken(user.getId(), user.getUsername(), claims.sessionId(), claims.sessionExpiresAt()))
                .refreshToken(refreshToken).build();
    }

    private AuthTokenPairVO createTokenPair(UserEntity user) {
        return tokenService.createTokenPair(user.getId(), user.getUsername());
    }

    private void revokeSession(AuthTokenClaimsVO claims) {
        tokenRevocationRepository.revokeSession(claims.sessionId(), claims.sessionExpiresAt());
        log.info("认证领域服务-用户登出完成 userId:{} sessionId:{}", claims.userId(), claims.sessionId());
    }

    private String normalizeUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new BusinessException(ResponseCode.EMPTY_USERNAME);
        }
        return username.trim();
    }

    private String normalizePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_PASSWORD);
        }
        return rawPassword;
    }
}

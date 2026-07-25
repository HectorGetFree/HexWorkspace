package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.model.entity.AuthSession;
import com.hector.domain.auth.service.IAuthService;
import com.hector.domain.auth.service.IPasswordService;
import com.hector.domain.auth.service.ITokenService;
import com.hector.domain.user.model.entity.User;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 认证领域服务实现。
 */
@Service
public class AuthServiceImpl implements IAuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final String TOKEN_PREFIX = "Bearer ";

    @Resource
    private IUserRepository userRepository;

    @Resource
    private IPasswordService passwordService;

    @Resource
    private ITokenService tokenService;

    @Override
    public AuthSession login(String username, String rawPassword) {
        log.info("认证领域服务-用户登录开始 username:{}", username);
        String normalizedUsername = normalizeUsername(username);
        String normalizedPassword = normalizePassword(rawPassword);

        User user = userRepository.findByUsername(normalizedUsername)
                .orElseThrow(() -> new BusinessException(ResponseCode.USERNAME_OR_PASSWORD_ERROR));

        if (!user.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_LOGIN_ERROR);
        }
        if (!passwordService.matches(normalizedPassword, user.getPasswordHash())) {
            throw new BusinessException(ResponseCode.USERNAME_OR_PASSWORD_ERROR);
        }

        AuthSession authSession = AuthSession.builder()
                .user(user)
                .token(tokenService.createToken(user.getId(), user.getUsername()))
                .build();
        log.info("认证领域服务-用户登录完成 userId:{} username:{}", user.getId(), user.getUsername());
        return authSession;
    }

    @Override
    public void logout(String token) {
        log.info("认证领域服务-用户登出开始 token:{}", token);
        Long userId = tokenService.parseUserId(extractToken(token));
        userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        log.info("认证领域服务-用户登出完成 userId:{}", userId);
    }

    @Override
    public User getCurrentUser(String token) {
        log.info("认证领域服务-查询当前用户开始 token:{}", token);
        Long userId = tokenService.parseUserId(extractToken(token));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        if (!user.isActive()) {
            throw new BusinessException(ResponseCode.USER_STATUS_UNAVAILABLE);
        }
        log.info("认证领域服务-查询当前用户完成 userId:{} username:{}", user.getId(), user.getUsername());
        return user;
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

    private String extractToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ResponseCode.EMPTY_TOKEN);
        }
        if (token.startsWith(TOKEN_PREFIX)) {
            return token.substring(TOKEN_PREFIX.length());
        }
        return token;
    }
}

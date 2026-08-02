package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.model.entity.AuthSessionEntity;
import com.hector.domain.auth.service.IAuthService;
import com.hector.domain.auth.service.IPasswordService;
import com.hector.domain.auth.service.ITokenService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 认证领域服务实现。
 * 当前仅负责登录校验与访问令牌签发。
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

        AuthSessionEntity authSessionEntity = AuthSessionEntity.builder()
                .user(userEntity)
                .token(tokenService.createToken(userEntity.getId(), userEntity.getUsername()))
                .build();
        log.info("认证领域服务-用户登录完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
        return authSessionEntity;
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

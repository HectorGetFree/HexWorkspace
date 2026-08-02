package com.hector.domain.user.service.impl;

import com.hector.domain.auth.service.IPasswordService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.model.valobj.UserStatusVO;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.domain.user.service.IUserService;
import com.hector.hexdoc.types.enums.ResponseCode;
import com.hector.hexdoc.types.exception.BusinessException;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户领域服务实现。
 */
@Service
public class UserServiceImpl implements IUserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    @Resource
    private IUserRepository userRepository;

    @Resource
    private IPasswordService passwordService;

    @Override
    @Transactional
    public UserEntity registerUser(String username, String rawPassword) {
        log.info("用户领域服务-注册用户开始 username:{}", username);
        String normalizedUsername = normalizeUsername(username);
        String normalizedPassword = normalizePassword(rawPassword);

        userRepository.findByUsername(normalizedUsername)
                .ifPresent(user -> {
                    throw new BusinessException(ResponseCode.USERNAME_DUPLICATE);
                });

        LocalDateTime now = LocalDateTime.now();
        UserEntity userEntity = UserEntity.builder()
                .username(normalizedUsername)
                .passwordHash(passwordService.encode(normalizedPassword))
                .status(UserStatusVO.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        UserEntity savedUserEntity = userRepository.save(userEntity);
        log.info("用户领域服务-注册用户完成 userId:{} username:{}", savedUserEntity.getId(), savedUserEntity.getUsername());
        return savedUserEntity;
    }

    @Override
    public UserEntity getCurrentUser(Long userId) {
        log.info("用户领域服务-查询当前用户开始 userId:{}", userId);
        UserEntity userEntity = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ResponseCode.USER_NOT_FOUND));
        log.info("用户领域服务-查询当前用户完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
        return userEntity;
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

package com.hector.infrastructure.repository;

import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.model.valobj.UserStatusVO;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.infrastructure.dao.IUserDao;
import com.hector.infrastructure.dao.po.User;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户仓储实现。
 * 基于 MySQL + MyBatis 完成持久化。
 */
@Repository
public class UserRepository implements IUserRepository {

    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);

    @Resource
    private IUserDao userDao;

    @Override
    public UserEntity save(UserEntity userEntity) {
        log.info("用户仓储-保存用户开始 username:{}", userEntity.getUsername());
        User user = User.builder()
                .username(userEntity.getUsername())
                .passwordHash(userEntity.getPasswordHash())
                .status(userEntity.getStatus().name())
                .createdAt(userEntity.getCreatedAt())
                .updatedAt(userEntity.getUpdatedAt())
                .build();
        userDao.insert(user);

        UserEntity savedUserEntity = toEntity(user);
        log.info("用户仓储-保存用户完成 userId:{} username:{}", savedUserEntity.getId(), savedUserEntity.getUsername());
        return savedUserEntity;
    }

    @Override
    public Optional<UserEntity> findByUsername(String username) {
        log.info("用户仓储-按用户名查询开始 username:{}", username);
        User user = userDao.queryUserByUsername(username);
        if (user == null) {
            return Optional.empty();
        }

        UserEntity userEntity = toEntity(user);
        log.info("用户仓储-按用户名查询完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
        return Optional.of(userEntity);
    }

    @Override
    public Optional<UserEntity> findById(Long userId) {
        log.info("用户仓储-按ID查询开始 userId:{}", userId);
        User user = userDao.queryUserById(userId);
        if (user == null) {
            return Optional.empty();
        }

        UserEntity userEntity = toEntity(user);
        log.info("用户仓储-按ID查询完成 userId:{} username:{}", userEntity.getId(), userEntity.getUsername());
        return Optional.of(userEntity);
    }

    private UserEntity toEntity(User user) {
        return UserEntity.builder()
                .id(user.getId())
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .status(UserStatusVO.valueOf(user.getStatus()))
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}

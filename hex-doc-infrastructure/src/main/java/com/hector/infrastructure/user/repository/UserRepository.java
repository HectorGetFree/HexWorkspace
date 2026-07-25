package com.hector.infrastructure.user.repository;

import com.hector.domain.user.model.entity.User;
import com.hector.domain.user.model.valobj.UserStatusVO;
import com.hector.domain.user.repository.IUserRepository;
import com.hector.infrastructure.dao.IUserDao;
import com.hector.infrastructure.dao.po.UserPO;
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
    public User save(User user) {
        log.info("用户仓储-保存用户开始 username:{}", user.getUsername());
        UserPO userPO = UserPO.builder()
                .username(user.getUsername())
                .passwordHash(user.getPasswordHash())
                .status(user.getStatus().name())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
        userDao.insert(userPO);

        User savedUser = toEntity(userPO);
        log.info("用户仓储-保存用户完成 userId:{} username:{}", savedUser.getId(), savedUser.getUsername());
        return savedUser;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        log.info("用户仓储-按用户名查询开始 username:{}", username);
        UserPO userPO = userDao.queryUserByUsername(username);
        if (userPO == null) {
            return Optional.empty();
        }

        User user = toEntity(userPO);
        log.info("用户仓储-按用户名查询完成 userId:{} username:{}", user.getId(), user.getUsername());
        return Optional.of(user);
    }

    @Override
    public Optional<User> findById(Long userId) {
        log.info("用户仓储-按ID查询开始 userId:{}", userId);
        UserPO userPO = userDao.queryUserById(userId);
        if (userPO == null) {
            return Optional.empty();
        }

        User user = toEntity(userPO);
        log.info("用户仓储-按ID查询完成 userId:{} username:{}", user.getId(), user.getUsername());
        return Optional.of(user);
    }

    private User toEntity(UserPO userPO) {
        return User.builder()
                .id(userPO.getId())
                .username(userPO.getUsername())
                .passwordHash(userPO.getPasswordHash())
                .status(UserStatusVO.valueOf(userPO.getStatus()))
                .createdAt(userPO.getCreatedAt())
                .updatedAt(userPO.getUpdatedAt())
                .build();
    }
}

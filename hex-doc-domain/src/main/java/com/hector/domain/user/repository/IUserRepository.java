package com.hector.domain.user.repository;

import com.hector.domain.user.model.entity.UserEntity;

import java.util.Optional;

/**
 * 用户仓储接口。
 */
public interface IUserRepository {

    /**
     * 保存用户。
     */
    UserEntity save(UserEntity userEntity);

    /**
     * 按用户名查询用户。
     */
    Optional<UserEntity> findByUsername(String username);

    /**
     * 按主键ID查询用户。
     */
    Optional<UserEntity> findById(Long userId);
}

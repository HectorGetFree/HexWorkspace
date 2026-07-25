package com.hector.domain.user.repository;

import com.hector.domain.user.model.entity.User;

import java.util.Optional;

/**
 * 用户仓储接口。
 */
public interface IUserRepository {

    /**
     * 保存用户。
     */
    User save(User user);

    /**
     * 按用户名查询用户。
     */
    Optional<User> findByUsername(String username);

    /**
     * 按主键ID查询用户。
     */
    Optional<User> findById(Long userId);
}

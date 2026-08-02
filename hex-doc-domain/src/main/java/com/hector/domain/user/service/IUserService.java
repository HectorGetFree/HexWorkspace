package com.hector.domain.user.service;

import com.hector.domain.user.model.entity.UserEntity;

/**
 * 用户领域服务接口。
 * 负责用户实体自身的生命周期和基础信息管理。
 */
public interface IUserService {

    /**
     * 注册用户。
     */
    UserEntity registerUser(String username, String rawPassword);

    /**
     * 查询当前用户。
     */
    UserEntity getCurrentUser(Long userId);
}

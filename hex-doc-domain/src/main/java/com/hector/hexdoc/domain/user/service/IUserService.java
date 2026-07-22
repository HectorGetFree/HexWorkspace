package com.hector.hexdoc.domain.user.service;

import com.hector.hexdoc.domain.user.model.entity.User;

/**
 * 用户领域服务接口。
 * 当前阶段先只暴露领域能力接口，不提供具体实现，用于约束后续注册、登录和当前用户查询的领域编排入口。
 */
public interface IUserService {

    /**
     * 注册用户。
     */
    User registerUser(String username, String rawPassword);

    /**
     * 用户登录。
     */
    User login(String username, String rawPassword);

    /**
     * 用户登出。
     */
    void logout(Long userId, String token);

    /**
     * 查询当前用户。
     */
    User getCurrentUser(Long userId);
}

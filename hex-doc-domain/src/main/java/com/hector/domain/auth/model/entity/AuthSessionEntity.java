package com.hector.domain.auth.model.entity;

import com.hector.domain.user.model.entity.UserEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 认证会话实体。
 * 用于表达一次成功认证后的用户身份和访问令牌。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSessionEntity {

    /**
     * 当前登录用户。
     */
    private UserEntity user;

    /**
     * 本次签发的访问令牌。
     */
    private String token;
}

package com.hector.domain.auth.service;

import com.hector.domain.auth.model.valobj.AccessTokenClaimsVO;

/**
 * 令牌服务接口。
 * 负责访问令牌的创建与解析，供登录流程与 Spring Security 过滤器复用。
 */
public interface ITokenService {

    /**
     * 创建访问令牌。
     */
    String createToken(Long userId, String username);

    /** 验证访问令牌并返回登录态校验所需声明。 */
    AccessTokenClaimsVO parseToken(String token);
}

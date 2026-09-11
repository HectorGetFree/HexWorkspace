package com.hector.domain.auth.service;

import com.hector.domain.auth.model.valobj.AuthTokenClaimsVO;
import com.hector.domain.auth.model.valobj.AuthTokenPairVO;

/**
 * 令牌服务接口。
 * 负责访问令牌的创建与解析，供登录流程与 Spring Security 过滤器复用。
 */
public interface ITokenService {

    /**
     * 创建访问令牌。
     */
    AuthTokenPairVO createTokenPair(Long userId, String username);

    /** 验证访问令牌并返回登录态校验所需声明。 */
    AuthTokenClaimsVO parseAccessToken(String token);

    /** 验证 refreshToken 并返回刷新登录态所需声明。 */
    AuthTokenClaimsVO parseRefreshToken(String token);

    /** 在同一未撤销会话中签发新的短期 accessToken。 */
    String createAccessToken(Long userId, String username, String sessionId, java.time.Instant sessionExpiresAt);
}

package com.hector.domain.auth.model.valobj;

/** 一次登录签发的一对访问令牌与刷新令牌。 */
public record AuthTokenPairVO(String accessToken, String refreshToken) {
}

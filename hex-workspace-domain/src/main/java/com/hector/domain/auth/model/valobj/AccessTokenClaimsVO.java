package com.hector.domain.auth.model.valobj;

import java.time.Instant;

/** 已通过签名与过期校验的访问令牌声明。 */
public record AccessTokenClaimsVO(Long userId, String tokenId, Instant expiresAt) {
}

package com.hector.domain.auth.model.valobj;

import java.time.Instant;

/** 已通过签名与过期校验的认证令牌声明。 */
public record AuthTokenClaimsVO(Long userId, String tokenId, String sessionId, Instant expiresAt,
                                Instant sessionExpiresAt, AuthTokenTypeVO tokenType) {
}

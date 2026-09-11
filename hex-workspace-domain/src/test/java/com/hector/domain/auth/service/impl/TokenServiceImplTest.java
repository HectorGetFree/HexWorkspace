package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.model.valobj.AuthTokenClaimsVO;
import com.hector.domain.auth.model.valobj.AuthTokenPairVO;
import com.hector.domain.auth.model.valobj.AuthTokenTypeVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 双令牌签发与会话标识测试。 */
class TokenServiceImplTest {

    @Test
    void shouldCreateTokenPairWithSameSessionAndDifferentPurposes() throws Exception {
        TokenServiceImpl tokenService = new TokenServiceImpl();
        setField(tokenService, "secret", "test-jwt-secret");
        setField(tokenService, "accessExpireMinutes", 15L);
        setField(tokenService, "refreshExpireDays", 30L);

        AuthTokenPairVO tokenPair = tokenService.createTokenPair(1L, "hector");
        AuthTokenClaimsVO accessClaims = tokenService.parseAccessToken(tokenPair.accessToken());
        AuthTokenClaimsVO refreshClaims = tokenService.parseRefreshToken(tokenPair.refreshToken());

        assertEquals(1L, accessClaims.userId());
        assertEquals(accessClaims.sessionId(), refreshClaims.sessionId());
        assertEquals(AuthTokenTypeVO.ACCESS, accessClaims.tokenType());
        assertEquals(AuthTokenTypeVO.REFRESH, refreshClaims.tokenType());
        assertFalse(accessClaims.tokenId().isBlank());
        assertTrue(refreshClaims.expiresAt().isAfter(accessClaims.expiresAt()));
        assertTrue(accessClaims.expiresAt().isAfter(Instant.now()));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

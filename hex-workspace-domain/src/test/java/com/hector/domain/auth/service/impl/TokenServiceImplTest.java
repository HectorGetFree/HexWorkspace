package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.model.valobj.AccessTokenClaimsVO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JWT 签发与单令牌撤销标识测试。 */
class TokenServiceImplTest {

    @Test
    void shouldCreateTokenWithUniqueTokenIdAndExpiration() throws Exception {
        TokenServiceImpl tokenService = new TokenServiceImpl();
        setField(tokenService, "secret", "test-jwt-secret");
        setField(tokenService, "expireHours", 1L);

        AccessTokenClaimsVO claims = tokenService.parseToken(tokenService.createToken(1L, "hector"));

        assertEquals(1L, claims.userId());
        assertFalse(claims.tokenId().isBlank());
        assertTrue(claims.expiresAt().isAfter(Instant.now()));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}

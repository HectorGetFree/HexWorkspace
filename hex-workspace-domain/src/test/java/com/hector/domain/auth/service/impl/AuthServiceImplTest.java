package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.model.valobj.AccessTokenClaimsVO;
import com.hector.domain.auth.repository.ITokenRevocationRepository;
import com.hector.domain.auth.service.ITokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 当前令牌登出行为测试。 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private ITokenService tokenService;

    @Mock
    private ITokenRevocationRepository tokenRevocationRepository;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void shouldRevokeCurrentTokenUntilItsExpiration() {
        Instant expiresAt = Instant.now().plusSeconds(600);
        AccessTokenClaimsVO claims = new AccessTokenClaimsVO(1L, "token-id", expiresAt);
        when(tokenService.parseToken("access-token")).thenReturn(claims);

        authService.logout("access-token");

        verify(tokenRevocationRepository).revoke("token-id", expiresAt);
    }
}

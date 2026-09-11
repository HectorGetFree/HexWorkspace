package com.hector.domain.auth.service.impl;

import com.hector.domain.auth.model.valobj.AuthTokenClaimsVO;
import com.hector.domain.auth.model.valobj.AuthTokenTypeVO;
import com.hector.domain.auth.repository.ITokenRevocationRepository;
import com.hector.domain.auth.service.ITokenService;
import com.hector.domain.user.model.entity.UserEntity;
import com.hector.domain.user.model.valobj.UserStatusVO;
import com.hector.domain.user.repository.IUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 当前令牌登出行为测试。 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private ITokenService tokenService;

    @Mock
    private ITokenRevocationRepository tokenRevocationRepository;

    @Mock
    private IUserRepository userRepository;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void shouldRevokeCurrentSessionUntilRefreshTokenExpiration() {
        Instant sessionExpiresAt = Instant.now().plusSeconds(600);
        AuthTokenClaimsVO claims = new AuthTokenClaimsVO(1L, "token-id", "session-id",
                Instant.now().plusSeconds(60), sessionExpiresAt, AuthTokenTypeVO.ACCESS);
        when(tokenService.parseAccessToken("access-token")).thenReturn(claims);

        authService.logout("access-token");

        verify(tokenRevocationRepository).revokeSession("session-id", sessionExpiresAt);
    }

    @Test
    void shouldIssueNewAccessTokenForActiveUnrevokedSession() {
        Instant sessionExpiresAt = Instant.now().plusSeconds(600);
        AuthTokenClaimsVO claims = new AuthTokenClaimsVO(1L, "refresh-id", "session-id",
                sessionExpiresAt, sessionExpiresAt, AuthTokenTypeVO.REFRESH);
        UserEntity user = UserEntity.builder().id(1L).username("hector").status(UserStatusVO.ACTIVE).build();
        when(tokenService.parseRefreshToken("refresh-token")).thenReturn(claims);
        when(tokenRevocationRepository.isSessionRevoked("session-id")).thenReturn(false);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(tokenService.createAccessToken(1L, "hector", "session-id", sessionExpiresAt)).thenReturn("new-access-token");

        assertEquals("new-access-token", authService.refresh("refresh-token").getToken());
    }
}

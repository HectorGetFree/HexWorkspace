package com.hector.domain.auth.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordServiceImplTest {

    private final PasswordServiceImpl passwordService = new PasswordServiceImpl();

    @Test
    void shouldGenerateDifferentHashesAndMatchOriginalPassword() {
        String firstHash = passwordService.encode("StrongPassword!123");
        String secondHash = passwordService.encode("StrongPassword!123");

        assertNotEquals(firstHash, secondHash);
        assertTrue(passwordService.matches("StrongPassword!123", firstHash));
        assertTrue(passwordService.matches("StrongPassword!123", secondHash));
    }

    @Test
    void shouldRejectWrongPasswordAndInvalidHash() {
        String encodedPassword = passwordService.encode("StrongPassword!123");

        assertFalse(passwordService.matches("WrongPassword!123", encodedPassword));
        assertFalse(passwordService.matches("StrongPassword!123", null));
        assertFalse(passwordService.matches("StrongPassword!123", "legacy-sha256-hash"));
    }
}

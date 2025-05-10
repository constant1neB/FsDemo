package com.example.fsdemo.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AppUser Domain Tests")
class AppUserTest {

    @Test
    @DisplayName("Default constructor should create an instance")
    void defaultConstructor() {
        AppUser user = new AppUser();
        assertThat(user).isNotNull();
        assertThat(user.isVerified()).isFalse();
    }

    @Test
    @DisplayName("Parameterized constructor should set fields correctly")
    void parameterizedConstructor() {
        String username = "testuser";
        String password = "password123";
        String role = "ADMIN";
        String email = "admin@example.com";
        AppUser user = new AppUser(username, password, role, email);

        assertThat(user.getUsername()).isEqualTo(username);
        assertThat(user.getPassword()).isEqualTo(password);
        assertThat(user.getRole()).isEqualTo(role);
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.isVerified()).isFalse();
        assertThat(user.getId()).isNull();
        assertThat(user.getVerificationTokenHash()).isNull();
        assertThat(user.getVerificationTokenExpiryDate()).isNull();
    }

    @Test
    @DisplayName("Setters should update field values")
    void settersUpdateValues() {
        AppUser user = new AppUser();
        Instant expiry = Instant.now();
        Long id = 1L;
        String tokenHash = "hashedToken";

        user.setId(id);
        user.setUsername("newUsername");
        user.setPassword("newPassword");
        user.setEmail("new@example.com");
        user.setVerified(true);
        user.setVerificationTokenHash(tokenHash);
        user.setVerificationTokenExpiryDate(expiry);

        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getUsername()).isEqualTo("newUsername");
        assertThat(user.getPassword()).isEqualTo("newPassword");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.isVerified()).isTrue();
        assertThat(user.getVerificationTokenHash()).isEqualTo(tokenHash);
        assertThat(user.getVerificationTokenExpiryDate()).isEqualTo(expiry);
    }
}
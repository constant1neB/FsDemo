package com.example.fsdemo.security;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDetailsServiceImpl Tests")
class UserDetailsServiceImplTest {

    @Mock
    private AppUserRepository repository;

    @InjectMocks
    private UserDetailsServiceImpl service;

    private AppUser verifiedUser;
    private AppUser unverifiedUser;
    private final String username = "testuser";
    private final String password = "hashedPassword";
    private final String role = "USER";

    @BeforeEach
    void setUp() {
        verifiedUser = new AppUser(username, password, role, "test@example.com");
        verifiedUser.setVerified(true);

        unverifiedUser = new AppUser(username, password, role, "test@example.com");
        unverifiedUser.setVerified(false);
    }

    @Test
    @DisplayName("loadUserByUsername should return UserDetails for verified user")
    void loadUserByUsername_SuccessVerifiedUser() {
        given(repository.findByUsername(username)).willReturn(Optional.of(verifiedUser));

        UserDetails userDetails = service.loadUserByUsername(username);

        assertThat(userDetails).isNotNull();
        assertThat(userDetails.getUsername()).isEqualTo(username);
        assertThat(userDetails.getPassword()).isEqualTo(password);
        assertThat(userDetails.getAuthorities()).hasSize(1);
        assertThat(userDetails.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_" + role);
        assertThat(userDetails.isEnabled()).isTrue();
        assertThat(userDetails.isAccountNonExpired()).isTrue();
        assertThat(userDetails.isCredentialsNonExpired()).isTrue();
        assertThat(userDetails.isAccountNonLocked()).isTrue();

        then(repository).should().findByUsername(username);
    }

    @Test
    @DisplayName("loadUserByUsername should throw UsernameNotFoundException if user not found")
    void loadUserByUsername_FailUserNotFound() {
        given(repository.findByUsername(username)).willReturn(Optional.empty());

        assertThatExceptionOfType(UsernameNotFoundException.class)
                .isThrownBy(() -> service.loadUserByUsername(username))
                .withMessage("User not found: " + username);

        then(repository).should().findByUsername(username);
    }

    @Test
    @DisplayName("loadUserByUsername should throw DisabledException if user is not verified")
    void loadUserByUsername_FailUserNotVerified() {
        given(repository.findByUsername(username)).willReturn(Optional.of(unverifiedUser));

        assertThatExceptionOfType(DisabledException.class)
                .isThrownBy(() -> service.loadUserByUsername(username))
                .withMessage("User account is not verified. Please check your email.");

        then(repository).should().findByUsername(username);
    }
}
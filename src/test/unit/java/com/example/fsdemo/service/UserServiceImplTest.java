package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.service.impl.UserServiceImpl;
import com.example.fsdemo.web.dto.RegistrationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl Tests")
class UserServiceImplTest {

    @Mock
    private AppUserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailService emailService;

    @InjectMocks
    private UserServiceImpl userService;

    @Captor
    private ArgumentCaptor<AppUser> userCaptor;
    @Captor
    private ArgumentCaptor<String> tokenCaptor;
    @Captor
    private ArgumentCaptor<String> baseUrlCaptor;

    private RegistrationRequest validRequest;
    private final String testUsername = "testuser";
    private final String testEmail = "test@example.com";
    private final String testPassword = "Password123!";
    private final String encodedPassword = "encodedPassword123";
    private final String appBaseUrl = "http://localhost:8080/api/auth";

    @BeforeEach
    void setUp() {
        validRequest = new RegistrationRequest(testUsername, testEmail, testPassword, testPassword);
        ReflectionTestUtils.setField(userService, "appBaseUrl", appBaseUrl);
        String tokenDuration = "PT24H";
        ReflectionTestUtils.setField(userService, "tokenDurationString", tokenDuration);
    }

    private void verifyUserSavedCorrectly(AppUser savedUser, boolean expectVerified, boolean expectToken) {
        assertThat(savedUser).isNotNull();
        assertThat(savedUser.getUsername()).isEqualTo("testuser");
        assertThat(savedUser.getEmail()).isEqualTo("test@example.com");
        assertThat(savedUser.getPassword()).isEqualTo("encodedPassword123");
        assertThat(savedUser.getRole()).isEqualTo("USER");
        assertThat(savedUser.isVerified()).isEqualTo(expectVerified);
        if (expectToken) {
            assertThat(savedUser.getVerificationTokenHash()).isNotBlank();
            assertThat(savedUser.getVerificationTokenExpiryDate()).isNotNull().isAfter(Instant.now());
        } else {
            assertThat(savedUser.getVerificationTokenHash()).isNull();
            assertThat(savedUser.getVerificationTokenExpiryDate()).isNull();
        }
    }

    private void givenUserRepositorySaveWillSetId() {
        given(userRepository.save(any(AppUser.class))).willAnswer(invocation -> {
            AppUser user = invocation.getArgument(0);
            if (user.getId() == null) {
                ReflectionTestUtils.setField(user, "id", 1L);
            }
            return user;
        });
    }

    @Nested
    @DisplayName("registerNewUser Tests")
    class RegisterNewUserTests {

        @Test
        @DisplayName("✅ Success: Should create new user, hash password, generate token, save, and trigger email")
        void registerNewUser_Success() {
            given(userRepository.findByUsername(testUsername)).willReturn(Optional.empty());
            given(userRepository.findByEmail(testEmail)).willReturn(Optional.empty());
            given(passwordEncoder.encode(testPassword)).willReturn(encodedPassword);
            givenUserRepositorySaveWillSetId();
            doNothing().when(emailService).sendVerificationEmail(any(AppUser.class), anyString(), anyString());

            userService.registerNewUser(validRequest);

            then(userRepository).should().save(userCaptor.capture());
            AppUser savedUser = userCaptor.getValue();
            verifyUserSavedCorrectly(savedUser, false, true);

            then(emailService).should().sendVerificationEmail(eq(savedUser), tokenCaptor.capture(), baseUrlCaptor.capture());
            assertThat(tokenCaptor.getValue()).isNotBlank();
            assertThat(baseUrlCaptor.getValue()).isEqualTo(appBaseUrl);

            then(userRepository).should().findByUsername(testUsername);
            then(userRepository).should().findByEmail(testEmail);
            then(passwordEncoder).should().encode(testPassword);
        }

        @Test
        @DisplayName("❌ Failure: Passwords do not match")
        void registerNewUser_PasswordsDoNotMatch() {
            RegistrationRequest mismatchRequest = new RegistrationRequest(testUsername, testEmail, testPassword, "WrongPassword");

            assertThatThrownBy(() -> userService.registerNewUser(mismatchRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("Passwords do not match");

            then(userRepository).should(never()).findByUsername(anyString());
            then(userRepository).should(never()).findByEmail(anyString());
            then(userRepository).should(never()).save(any(AppUser.class));
            then(emailService).should(never()).sendVerificationEmail(any(), any(), any());
            then(passwordEncoder).should(never()).encode(anyString());
        }

        @Test
        @DisplayName("❌ Failure: Username already exists")
        void registerNewUser_UsernameExists() {
            given(userRepository.findByUsername(testUsername)).willReturn(Optional.of(new AppUser()));

            assertThatThrownBy(() -> userService.registerNewUser(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                    .hasMessageContaining("Username or email already exists");

            then(userRepository).should().findByUsername(testUsername);
            then(userRepository).should(never()).findByEmail(anyString());
            then(userRepository).should(never()).save(any(AppUser.class));
            then(emailService).should(never()).sendVerificationEmail(any(), any(), any());
            then(passwordEncoder).should(never()).encode(anyString());
        }

        @Test
        @DisplayName("❌ Failure: Email already exists (verified user)")
        void registerNewUser_EmailExistsVerified() {
            AppUser existingVerifiedUser = new AppUser("otherUser", encodedPassword, "USER", testEmail);
            existingVerifiedUser.setVerified(true);
            given(userRepository.findByUsername(testUsername)).willReturn(Optional.empty());
            given(userRepository.findByEmail(testEmail)).willReturn(Optional.of(existingVerifiedUser));

            assertThatThrownBy(() -> userService.registerNewUser(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                    .hasMessageContaining("Username or email already exists");

            then(userRepository).should().findByUsername(testUsername);
            then(userRepository).should().findByEmail(testEmail);
            then(userRepository).should(never()).save(any(AppUser.class));
            then(emailService).should(never()).sendVerificationEmail(any(), any(), any());
            then(passwordEncoder).should(never()).encode(anyString());
        }

        @Test
        @DisplayName("❌ Failure: Email already exists (unverified user)")
        void registerNewUser_EmailExistsUnverified() {
            AppUser existingUnverifiedUser = new AppUser("otherUser", encodedPassword, "USER", testEmail);
            existingUnverifiedUser.setVerified(false);
            existingUnverifiedUser.setVerificationTokenHash("somehash");
            existingUnverifiedUser.setVerificationTokenExpiryDate(Instant.now().plus(Duration.ofHours(1)));
            given(userRepository.findByUsername(testUsername)).willReturn(Optional.empty());
            given(userRepository.findByEmail(testEmail)).willReturn(Optional.of(existingUnverifiedUser));

            assertThatThrownBy(() -> userService.registerNewUser(validRequest))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                    .hasMessageContaining("Username or email already exists");

            then(userRepository).should().findByUsername(testUsername);
            then(userRepository).should().findByEmail(testEmail);
            then(userRepository).should(never()).save(any(AppUser.class));
            then(emailService).should(never()).sendVerificationEmail(any(), any(), any());
            then(passwordEncoder).should(never()).encode(anyString());
        }
    }

    @Nested
    @DisplayName("verifyUser Tests")
    class VerifyUserTests {
        private final String rawToken = "rawValidTokenValue12345";
        private String hashedToken;

        @BeforeEach
        void hashTokenForTest() {
            hashedToken = UserServiceImpl.hashToken(rawToken);
        }

        @Test
        @DisplayName("✅ Success: Valid token, unverified user")
        void verifyUser_Success() {
            AppUser userToVerify = new AppUser(testUsername, encodedPassword, "USER", testEmail);
            userToVerify.setVerified(false);
            userToVerify.setVerificationTokenHash(hashedToken);
            userToVerify.setVerificationTokenExpiryDate(Instant.now().plus(Duration.ofHours(1)));

            given(userRepository.findByVerificationTokenHash(hashedToken)).willReturn(Optional.of(userToVerify));
            givenUserRepositorySaveWillSetId();

            boolean result = userService.verifyUser(rawToken);

            assertThat(result).isTrue();
            then(userRepository).should().save(userCaptor.capture());
            AppUser savedUser = userCaptor.getValue();
            verifyUserSavedCorrectly(savedUser, true, false); // Verified, token cleared
        }

        @Test
        @DisplayName("✅ Success (Idempotent): User already verified, token present")
        void verifyUser_AlreadyVerifiedTokenPresent() {
            AppUser alreadyVerifiedUser = new AppUser(testUsername, encodedPassword, "USER", testEmail);
            alreadyVerifiedUser.setVerified(true);
            alreadyVerifiedUser.setVerificationTokenHash(hashedToken);
            alreadyVerifiedUser.setVerificationTokenExpiryDate(Instant.now().plus(Duration.ofHours(1)));

            given(userRepository.findByVerificationTokenHash(hashedToken)).willReturn(Optional.of(alreadyVerifiedUser));
            givenUserRepositorySaveWillSetId();

            boolean result = userService.verifyUser(rawToken);

            assertThat(result).isTrue();
            then(userRepository).should().save(userCaptor.capture());
            AppUser savedUser = userCaptor.getValue();
            verifyUserSavedCorrectly(savedUser, true, false);
        }

        @Test
        @DisplayName("✅ Success (Idempotent): User already verified, token already cleared")
        void verifyUser_AlreadyVerifiedTokenCleared() {
            AppUser alreadyVerifiedUser = new AppUser(testUsername, encodedPassword, "USER", testEmail);
            alreadyVerifiedUser.setVerified(true);
            alreadyVerifiedUser.setVerificationTokenHash(null);
            alreadyVerifiedUser.setVerificationTokenExpiryDate(null);

            given(userRepository.findByVerificationTokenHash(hashedToken)).willReturn(Optional.of(alreadyVerifiedUser));

            boolean result = userService.verifyUser(rawToken);

            assertThat(result).isTrue();
            then(userRepository).should(never()).save(any(AppUser.class));
        }


        @Test
        @DisplayName("❌ Failure: Token not found")
        void verifyUser_TokenNotFound() {
            given(userRepository.findByVerificationTokenHash(hashedToken)).willReturn(Optional.empty());

            boolean result = userService.verifyUser(rawToken);

            assertThat(result).isFalse();
            then(userRepository).should(never()).save(any(AppUser.class));
        }

        @Test
        @DisplayName("❌ Failure: Token expired")
        void verifyUser_TokenExpired() {
            AppUser userWithExpiredToken = new AppUser(testUsername, encodedPassword, "USER", testEmail);
            userWithExpiredToken.setVerified(false);
            userWithExpiredToken.setVerificationTokenHash(hashedToken);
            userWithExpiredToken.setVerificationTokenExpiryDate(Instant.now().minus(Duration.ofMinutes(1))); // Expired

            given(userRepository.findByVerificationTokenHash(hashedToken)).willReturn(Optional.of(userWithExpiredToken));

            boolean result = userService.verifyUser(rawToken);

            assertThat(result).isFalse();
            then(userRepository).should(never()).save(any(AppUser.class));
        }

        @Test
        @DisplayName("❌ Failure: Blank token")
        void verifyUser_BlankToken() {
            boolean result = userService.verifyUser(" ");
            assertThat(result).isFalse();
            then(userRepository).should(never()).findByVerificationTokenHash(anyString());
            then(userRepository).should(never()).save(any(AppUser.class));
        }

        @Test
        @DisplayName("❌ Failure: Null token")
        void verifyUser_NullToken() {
            boolean result = userService.verifyUser(null);
            assertThat(result).isFalse();
            then(userRepository).should(never()).findByVerificationTokenHash(anyString());
            then(userRepository).should(never()).save(any(AppUser.class));
        }
    }

    @Nested
    @DisplayName("resendVerificationEmail Tests")
    class ResendVerificationEmailTests {

        @Test
        @DisplayName("✅ Success: User found, unverified - should update token and trigger email")
        void resendVerificationEmail_SuccessUnverified() {
            AppUser unverifiedUser = new AppUser(testUsername, encodedPassword, "USER", testEmail);
            unverifiedUser.setVerified(false);
            unverifiedUser.setVerificationTokenHash("oldHash");
            unverifiedUser.setVerificationTokenExpiryDate(Instant.now().minus(Duration.ofHours(1))); // Expired

            given(userRepository.findByEmail(testEmail)).willReturn(Optional.of(unverifiedUser));
            givenUserRepositorySaveWillSetId();
            doNothing().when(emailService).sendVerificationEmail(any(AppUser.class), anyString(), anyString()); // Stub needed here

            userService.resendVerificationEmail(testEmail);

            then(userRepository).should().save(userCaptor.capture());
            AppUser savedUser = userCaptor.getValue();
            assertThat(savedUser.getVerificationTokenHash()).isNotEqualTo("oldHash").isNotBlank();
            assertThat(savedUser.getVerificationTokenExpiryDate()).isNotNull().isAfter(Instant.now());
            assertThat(savedUser.isVerified()).isFalse();

            then(emailService).should().sendVerificationEmail(eq(savedUser), tokenCaptor.capture(), baseUrlCaptor.capture());
            assertThat(tokenCaptor.getValue()).isNotBlank();
            assertThat(baseUrlCaptor.getValue()).isEqualTo(appBaseUrl);
        }

        @Test
        @DisplayName("✅ Success (No-op): Email not found")
        void resendVerificationEmail_EmailNotFound() {
            given(userRepository.findByEmail(testEmail)).willReturn(Optional.empty());

            userService.resendVerificationEmail(testEmail);

            then(userRepository).should().findByEmail(testEmail);
            then(userRepository).should(never()).save(any(AppUser.class));
            then(emailService).should(never()).sendVerificationEmail(any(), any(), any());
        }

        @Test
        @DisplayName("✅ Success (No-op): User already verified")
        void resendVerificationEmail_AlreadyVerified() {
            AppUser verifiedUser = new AppUser(testUsername, encodedPassword, "USER", testEmail);
            verifiedUser.setVerified(true);

            given(userRepository.findByEmail(testEmail)).willReturn(Optional.of(verifiedUser));

            userService.resendVerificationEmail(testEmail);

            then(userRepository).should().findByEmail(testEmail);
            then(userRepository).should(never()).save(any(AppUser.class));
            then(emailService).should(never()).sendVerificationEmail(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Token Hashing Tests")
    class TokenHashingTests {

        @Test
        @DisplayName("hashToken should produce consistent output for same input")
        void hashToken_ConsistentOutput() {
            String token1 = "mySecretToken123";
            String hash1 = UserServiceImpl.hashToken(token1);
            String hash2 = UserServiceImpl.hashToken(token1);

            assertThat(hash1)
                    .isNotBlank()
                    .isEqualTo(hash2)
                    .hasSize(64);
        }

        @Test
        @DisplayName("hashToken should produce different output for different input")
        void hashToken_DifferentOutput() {
            String token1 = "mySecretToken123";
            String token2 = "mySecretToken456";
            String hash1 = UserServiceImpl.hashToken(token1);
            String hash2 = UserServiceImpl.hashToken(token2);

            assertThat(hash1).isNotBlank();
            assertThat(hash2).isNotBlank();
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("hashToken should handle empty string")
        void hashToken_EmptyString() {
            String hash = UserServiceImpl.hashToken("");
            assertThat(hash)
                    .isNotBlank()
                    .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        }

        @Test
        @DisplayName("generateSecureToken should produce URL-safe base64 string")
        void generateSecureToken_FormatAndLength() {
            String token = ReflectionTestUtils.invokeMethod(userService, "generateSecureToken");

            assertThat(token)
                    .isNotNull()
                    .isNotBlank()
                    .doesNotContain("+", "/")
                    .matches("^[A-Za-z0-9_-]+$");
            assertThat(token.length()).isGreaterThanOrEqualTo(86).isLessThanOrEqualTo(88);
        }
    }
}
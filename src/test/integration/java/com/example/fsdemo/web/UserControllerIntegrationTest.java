package com.example.fsdemo.web;

import com.example.fsdemo.service.UserService;
import com.example.fsdemo.web.dto.RegistrationRequest;
import com.example.fsdemo.web.dto.ResendVerificationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import com.example.fsdemo.security.JwtService;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;


@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("UserController Registration & Verification Integration Tests")
@TestPropertySource(properties = "app.frontend-base-url=http://test-frontend.url")
class UserControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private AuthenticationManager authenticationManager;

    private RegistrationRequest validRegistrationRequest;
    private ResendVerificationRequest validResendRequest;

    private static final String TEST_FRONTEND_BASE_URL = "http://test-frontend.url";

    @BeforeEach
    void setUp() {
        validRegistrationRequest = new RegistrationRequest(
                "testuser",
                "test@example.com",
                "Password123!",
                "Password123!"
        );
        validResendRequest = new ResendVerificationRequest("test@example.com");
    }

    private String asJsonString(final Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("POST /api/auth/register")
    class RegisterEndpointTests {

        @Test
        @DisplayName("✅ Should return 201 Created on successful registration")
        void registerUser_Success() throws Exception {
            doNothing().when(userService).registerNewUser(any(RegistrationRequest.class));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(validRegistrationRequest)))
                    .andExpect(status().isCreated());

            verify(userService).registerNewUser(validRegistrationRequest);
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for mismatched passwords (simulated service error)")
        void registerUser_FailMismatchedPasswords() throws Exception {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match.");
            doThrow(ex).when(userService).registerNewUser(any(RegistrationRequest.class));

            RegistrationRequest mismatchRequest = new RegistrationRequest(
                    "testuser", "test@example.com", "Password123!", "DifferentPassword");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(mismatchRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.detail").value(containsString("Passwords do not match.")));

            verify(userService).registerNewUser(mismatchRequest);
        }

        @Test
        @DisplayName("❌ Should return 409 Conflict for duplicate username or email (simulated service error)")
        void registerUser_FailDuplicateUserOrEmail() throws Exception {
            ResponseStatusException ex = new ResponseStatusException(HttpStatus.CONFLICT, "Username or email already exists");
            doThrow(ex).when(userService).registerNewUser(any(RegistrationRequest.class));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(validRegistrationRequest)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.title").value("Conflict"))
                    .andExpect(jsonPath("$.detail").value(containsString("Username or email already exists")));

            verify(userService).registerNewUser(validRegistrationRequest);
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for invalid username format (DTO validation)")
        void registerUser_FailInvalidUsernameFormat() throws Exception {
            RegistrationRequest invalidRequest = new RegistrationRequest(
                    "test-user!", "test@example.com", "Password123!", "Password123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.errors", hasKey("username")));

            verify(userService, never()).registerNewUser(any(RegistrationRequest.class));
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for invalid email format (DTO validation)")
        void registerUser_FailInvalidEmailFormat() throws Exception {
            RegistrationRequest invalidRequest = new RegistrationRequest(
                    "testuser", "not-an-email", "Password123!", "Password123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.errors", hasKey("email")))
                    .andExpect(jsonPath("$.errors.email").value(containsString("well-formed email address")));

            verify(userService, never()).registerNewUser(any(RegistrationRequest.class));
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for short password (DTO validation)")
        void registerUser_FailShortPassword() throws Exception {
            RegistrationRequest invalidRequest = new RegistrationRequest(
                    "testuser", "test@example.com", "Short1!", "Short1!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.errors", hasKey("password")))
                    .andExpect(jsonPath("$.errors.password").value(containsString("size must be between 12 and 70")));

            verify(userService, never()).registerNewUser(any(RegistrationRequest.class));
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for blank username (DTO validation)")
        void registerUser_FailBlankUsername() throws Exception {
            RegistrationRequest invalidRequest = new RegistrationRequest(
                    " ", "test@example.com", "Password123!", "Password123!");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.errors", hasKey("username")));

            verify(userService, never()).registerNewUser(any(RegistrationRequest.class));
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for blank password confirmation (DTO validation)")
        void registerUser_FailBlankPasswordConfirmation() throws Exception {
            RegistrationRequest invalidRequest = new RegistrationRequest(
                    "testuser", "test@example.com", "Password123!", " ");

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.errors", hasKey("passwordConfirmation")))
                    .andExpect(jsonPath("$.errors.passwordConfirmation").value(containsString("cannot be blank")));

            verify(userService, never()).registerNewUser(any(RegistrationRequest.class));
        }
    }

    @Nested
    @DisplayName("GET /api/auth/verify-email")
    class VerifyEmailEndpointTests {

        @Test
        @DisplayName("✅ Should redirect to frontend with verified=true on successful verification")
        void verifyEmail_Success() throws Exception {
            String validToken = "valid-verification-token-123";
            when(userService.verifyUser(validToken)).thenReturn(true);

            mockMvc.perform(get("/api/auth/verify-email")
                            .param("token", validToken))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(TEST_FRONTEND_BASE_URL + "/login?verified=true"));

            verify(userService).verifyUser(validToken);
        }

        @Test
        @DisplayName("❌ Should redirect to frontend with error=verification_failed for invalid or expired token")
        void verifyEmail_FailInvalidToken() throws Exception {
            String invalidToken = "invalid-or-expired-token-456";
            when(userService.verifyUser(invalidToken)).thenReturn(false);

            mockMvc.perform(get("/api/auth/verify-email")
                            .param("token", invalidToken))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(TEST_FRONTEND_BASE_URL + "/login?error=verification_failed"));

            verify(userService).verifyUser(invalidToken);
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request if token parameter is missing")
        void verifyEmail_FailMissingToken() throws Exception {
            mockMvc.perform(get("/api/auth/verify-email"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.detail").value(containsString("Required request parameter 'token'")));

            verify(userService, never()).verifyUser(anyString());
        }

        @Test
        @DisplayName("❌ Should redirect to frontend with error=verification_failed if token parameter is blank")
        void verifyEmail_FailBlankToken() throws Exception {
            String blankToken = " ";
            when(userService.verifyUser(blankToken)).thenReturn(false);

            mockMvc.perform(get("/api/auth/verify-email")
                            .param("token", blankToken))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl(TEST_FRONTEND_BASE_URL + "/login?error=verification_failed"));

            verify(userService).verifyUser(blankToken);
        }
    }

    @Nested
    @DisplayName("POST /api/auth/resend-verification")
    class ResendVerificationEndpointTests {

        @Test
        @DisplayName("✅ Should return 202 Accepted when request is valid (general success case)")
        void resendVerification_Success() throws Exception {
            doNothing().when(userService).resendVerificationEmail(anyString());

            mockMvc.perform(post("/api/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(validResendRequest)))
                    .andExpect(status().isAccepted());

            verify(userService).resendVerificationEmail(validResendRequest.email());
        }

        @Test
        @DisplayName("✅ Should return 202 Accepted even if email doesn't exist (hides info)")
        void resendVerification_SuccessEmailNotFound() throws Exception {
            doNothing().when(userService).resendVerificationEmail(anyString());

            ResendVerificationRequest nonExistentEmailRequest = new ResendVerificationRequest("notfound@example.com");

            mockMvc.perform(post("/api/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(nonExistentEmailRequest)))
                    .andExpect(status().isAccepted());

            verify(userService).resendVerificationEmail(nonExistentEmailRequest.email());
        }

        @Test
        @DisplayName("✅ Should return 202 Accepted even if user is already verified (hides info)")
        void resendVerification_SuccessAlreadyVerified() throws Exception {
            ResendVerificationRequest alreadyVerifiedRequest = new ResendVerificationRequest("verified@example.com");
            doNothing().when(userService).resendVerificationEmail(alreadyVerifiedRequest.email());

            mockMvc.perform(post("/api/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(alreadyVerifiedRequest)))
                    .andExpect(status().isAccepted());

            verify(userService).resendVerificationEmail(alreadyVerifiedRequest.email());
        }


        @Test
        @DisplayName("❌ Should return 400 Bad Request for invalid email format (DTO validation)")
        void resendVerification_FailInvalidEmail() throws Exception {
            ResendVerificationRequest invalidRequest = new ResendVerificationRequest("invalid-email-format");

            mockMvc.perform(post("/api/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.errors", hasKey("email")))
                    .andExpect(jsonPath("$.errors.email").value(containsString("well-formed email address")));

            verify(userService, never()).resendVerificationEmail(anyString());
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for empty email (DTO validation)")
        void resendVerification_FailEmptyEmail() throws Exception {
            ResendVerificationRequest invalidRequest = new ResendVerificationRequest("");

            mockMvc.perform(post("/api/auth/resend-verification")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(asJsonString(invalidRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.errors", hasKey("email")))
                    .andExpect(jsonPath("$.errors.email", anyOf(
                            containsString("Email cannot be blank"),
                            containsString("Email must be a well-formed email address")
                    )));

            verify(userService, never()).resendVerificationEmail(anyString());
        }
    }
}
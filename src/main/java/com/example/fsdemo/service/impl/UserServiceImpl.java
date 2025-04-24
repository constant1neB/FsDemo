package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.service.EmailService;
import com.example.fsdemo.service.UserService;
import com.example.fsdemo.web.dto.RegistrationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final String DEFAULT_USER_ROLE = "USER";
    private static final String REGISTRATION_CONFLICT_MESSAGE = "Unable to register with the provided details.";
    private static final int VERIFICATION_TOKEN_BYTES = 64;

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${app.verification.token.duration}")
    private String tokenDurationString; // Keep using ISO-8601 duration format (e.g., PT24H)

    public UserServiceImpl(AppUserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public AppUser registerNewUser(RegistrationRequest registrationRequest) {
        log.info("Attempting registration for username: {}", registrationRequest.username());

        // 1. Password confirmation check removed - Rely on DTO validation (@Valid) for field constraints.

        // 2. Check for existing username
        if (userRepository.findByUsername(registrationRequest.username()).isPresent()) {
            log.warn("Registration failed: Username '{}' already exists.", registrationRequest.username());
            throw new ResponseStatusException(HttpStatus.CONFLICT, REGISTRATION_CONFLICT_MESSAGE);
        }

        // 3. Check for existing email (Handle verified vs unverified)
        Optional<AppUser> existingUserOpt = userRepository.findByEmail(registrationRequest.email());

        if (existingUserOpt.isPresent()) {
            AppUser existingUser = existingUserOpt.get();
            if (existingUser.isVerified()) {
                // Email already exists and is verified - Cannot re-register
                log.warn("Registration failed: Email '{}' already exists and is verified.", registrationRequest.email());
                throw new ResponseStatusException(HttpStatus.CONFLICT, REGISTRATION_CONFLICT_MESSAGE);
            } else {
                // Email exists but is NOT verified - Update existing record and resend verification
                log.info("Email '{}' exists but is unverified. Updating password and resending verification.", registrationRequest.email());
                return updateUnverifiedUserAndResendVerification(existingUser, registrationRequest.password());
            }
        }

        // 4. No existing user with this email, proceed with new user creation
        return createNewUserAndSendVerification(registrationRequest);
    }

    @Override
    @Transactional
    public boolean verifyUser(String token) {
        Optional<AppUser> userOpt = userRepository.findByVerificationToken(token);

        if (userOpt.isEmpty()) {
            log.warn("Verification failed: Token not found.");
            return false; // Indicate failure: Token invalid
        }

        AppUser user = userOpt.get();

        // Idempotency: If already verified, clear token info and return success.
        if (user.isVerified()) {
            log.warn("Verification attempt for already verified user: {}", user.getUsername());
            if (user.getVerificationToken() != null) {
                user.setVerificationToken(null);
                user.setVerificationTokenExpiryDate(null);
                userRepository.save(user);
            }
            return true;
        }

        // Check expiry
        if (user.getVerificationTokenExpiryDate() == null || user.getVerificationTokenExpiryDate().isBefore(Instant.now())) {
            log.warn("Verification failed: Token expired for user: {}", user.getUsername());
            // Don't clear the token here, allow resend
            return false; // Indicate failure: Token expired
        }

        // Verification Success!
        user.setVerified(true);
        user.setVerificationToken(null); // Invalidate the token
        user.setVerificationTokenExpiryDate(null);
        userRepository.save(user);

        log.info("Successfully verified email for user: {}", user.getUsername());
        return true;
    }

    @Override
    @Transactional
    public void resendVerificationEmail(String email) {
        Optional<AppUser> userOpt = userRepository.findByEmail(email);

        if (userOpt.isEmpty()) {
            log.info("Resend verification request for non-existent email: {}. No action taken.", email);
            return; // Do nothing, don't reveal email existence
        }

        AppUser user = userOpt.get();

        if (user.isVerified()) {
            log.info("Resend verification request for already verified user: {}. No action taken.", email);
            return; // Do nothing
        }

        // User exists and is not verified, generate new token and send
        log.info("Resending verification email for user: {}", user.getUsername());
        String token = generateSecureToken();
        Instant expiryDate = Instant.now().plus(Duration.parse(tokenDurationString));

        user.setVerificationToken(token);
        user.setVerificationTokenExpiryDate(expiryDate);
        AppUser savedUser = userRepository.save(user);

        // Send email asynchronously
        triggerVerificationEmail(savedUser, token);
    }

    // Helper Methods

    /**
     * Creates a new user, saves them, and triggers the verification email.
     */
    private AppUser createNewUserAndSendVerification(RegistrationRequest registrationRequest) {
        String hashedPassword = passwordEncoder.encode(registrationRequest.password());
        AppUser newUser = new AppUser(
                registrationRequest.username(),
                hashedPassword,
                DEFAULT_USER_ROLE,
                registrationRequest.email()
        );

        String token = generateSecureToken();
        Instant expiryDate = Instant.now().plus(Duration.parse(tokenDurationString));

        newUser.setVerificationToken(token);
        newUser.setVerificationTokenExpiryDate(expiryDate);
        newUser.setVerified(false); // Ensure it's false

        AppUser savedUser = userRepository.save(newUser);
        log.info("Successfully registered new user '{}' with ID: {}. Verification pending.", savedUser.getUsername(), savedUser.getId());

        triggerVerificationEmail(savedUser, token);
        return savedUser;
    }

    /**
     * Updates an existing unverified user's password, generates a new token,
     * saves, and triggers the verification email.
     */
    private AppUser updateUnverifiedUserAndResendVerification(AppUser existingUser, String newPassword) {
        String hashedPassword = passwordEncoder.encode(newPassword);
        existingUser.setPassword(hashedPassword); // Update password

        String token = generateSecureToken();
        Instant expiryDate = Instant.now().plus(Duration.parse(tokenDurationString));
        existingUser.setVerificationToken(token);
        existingUser.setVerificationTokenExpiryDate(expiryDate);

        AppUser savedUser = userRepository.save(existingUser);
        log.info("Re-initiated verification for existing unverified user '{}' with ID: {}", savedUser.getUsername(), savedUser.getId());

        triggerVerificationEmail(savedUser, token);
        return savedUser;
    }

    /**
     * Generates a cryptographically secure, URL-safe Base64 encoded token.
     */
    private String generateSecureToken() {
        byte[] randomBytes = new byte[VERIFICATION_TOKEN_BYTES];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Triggers the asynchronous email sending.
     */
    private void triggerVerificationEmail(AppUser user, String token) {
        try {
            emailService.sendVerificationEmail(user, token, appBaseUrl);
        } catch (Exception e) {
            log.error("Error triggering verification email for user {} (ID: {}).", user.getUsername(), user.getId(), e);
        }
    }
}
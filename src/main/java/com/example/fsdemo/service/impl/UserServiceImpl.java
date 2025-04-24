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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final String DEFAULT_USER_ROLE = "USER";
    private static final String REGISTRATION_CONFLICT_MESSAGE = "Username or email already exists or is pending verification. Please check your input or try logging in.";
    private static final int VERIFICATION_TOKEN_BYTES = 64;
    private static final String TOKEN_HASH_ALGORITHM = "SHA-256";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.base-url}")
    private String appBaseUrl;

    @Value("${app.verification.token.duration}")
    private String tokenDurationString;

    public UserServiceImpl(AppUserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    @Override
    @Transactional
    public void registerNewUser(RegistrationRequest registrationRequest) {
        log.info("Attempting registration for username: {}", registrationRequest.username());

        // 1. Check password confirmation
        if (!Objects.equals(registrationRequest.password(), registrationRequest.passwordConfirmation())) {
            log.warn("Registration failed: Passwords do not match for username attempt: {}", registrationRequest.username());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match.");
        }

        // 2. Check for existing username
        if (userRepository.findByUsername(registrationRequest.username()).isPresent()) {
            log.warn("Registration failed: Username '{}' already exists.", registrationRequest.username());
            // Use the generic conflict message
            throw new ResponseStatusException(HttpStatus.CONFLICT, REGISTRATION_CONFLICT_MESSAGE);
        }

        // 3. Check for existing email
        Optional<AppUser> existingUserOpt = userRepository.findByEmail(registrationRequest.email());

        if (existingUserOpt.isPresent()) {
            // Regardless of verified status, if the email exists, fail the registration.
            // This prevents overwriting passwords for unverified users and avoids leaking verification status.
            // Use the generic conflict message
            throw new ResponseStatusException(HttpStatus.CONFLICT, REGISTRATION_CONFLICT_MESSAGE);
        }

        // 4. Email does not exist, proceed to create new user
        createNewUserAndSendVerification(registrationRequest);
    }

    @Override
    @Transactional
    public boolean verifyUser(String rawToken) { // Parameter is the raw token from URL
        if (rawToken == null || rawToken.isBlank()) {
            log.warn("Verification attempt with blank token.");
            return false;
        }

        String hashedToken = hashToken(rawToken);
        Optional<AppUser> userOpt = userRepository.findByVerificationTokenHash(hashedToken);

        if (userOpt.isEmpty()) {
            log.warn("Verification failed: Token hash not found.");
            return false; // Indicate failure: Token invalid
        }

        AppUser user = userOpt.get();

        // Idempotency: If already verified, clear token info and return success.
        if (user.isVerified()) {
            log.info("Verification attempt for already verified user: {}. Treating as success.", user.getUsername());
            if (user.getVerificationTokenHash() != null) {
                user.setVerificationTokenHash(null); // Clear hash
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
        user.setVerificationTokenHash(null); // Invalidate the token hash
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
            return;
        }

        // User exists and is not verified, generate new token/hash and send
        log.info("Resending verification email for user: {}", user.getUsername());
        String rawToken = generateSecureToken();
        String hashedToken = hashToken(rawToken);
        Instant expiryDate = Instant.now().plus(Duration.parse(tokenDurationString));

        user.setVerificationTokenHash(hashedToken); // Store hash
        user.setVerificationTokenExpiryDate(expiryDate);
        AppUser savedUser = userRepository.save(user);

        triggerVerificationEmail(savedUser, rawToken);
    }

    // --- Helper Methods ---

    /**
     * Creates a new user, saves them, generates token/hash/expiry, and triggers the verification email.
     */
    private void createNewUserAndSendVerification(RegistrationRequest registrationRequest) {
        String hashedPassword = passwordEncoder.encode(registrationRequest.password());
        AppUser newUser = new AppUser(
                registrationRequest.username(),
                hashedPassword,
                DEFAULT_USER_ROLE,
                registrationRequest.email()
        );

        String rawToken = generateSecureToken();
        String hashedToken = hashToken(rawToken);
        Instant expiryDate = Instant.now().plus(Duration.parse(tokenDurationString));

        newUser.setVerificationTokenHash(hashedToken); // Store hash
        newUser.setVerificationTokenExpiryDate(expiryDate);
        newUser.setVerified(false); // Ensure it's false

        AppUser savedUser = userRepository.save(newUser);
        log.info("Successfully registered new user '{}' with ID: {}. Verification pending.", savedUser.getUsername(), savedUser.getId());

        triggerVerificationEmail(savedUser, rawToken); // Send raw token
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
     * Hashes the provided token using SHA-256 and returns the hex representation.
     * Throws IllegalStateException if SHA-256 is not available.
     */
    public static String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance(TOKEN_HASH_ALGORITHM);
            byte[] hashBytes = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(TOKEN_HASH_ALGORITHM + " algorithm not available", e);
        }
    }


    /**
     * Triggers the asynchronous email sending.
     */
    private void triggerVerificationEmail(AppUser user, String rawToken) { // Takes raw token
        try {
            // Pass the raw token to the email service
            emailService.sendVerificationEmail(user, rawToken, appBaseUrl);
        } catch (Exception e) {
            log.error("Error triggering verification email for user {} (ID: {}).", user.getUsername(), user.getId(), e);
            // Consider adding more robust error handling here if email failure is critical
        }
    }

}
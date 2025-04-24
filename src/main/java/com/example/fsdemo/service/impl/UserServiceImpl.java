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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final String DEFAULT_USER_ROLE = "USER";
    private static final String DUPLICATE_USER_MESSAGE = "Username or email already taken";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService; // Inject EmailService

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
    public AppUser registerNewUser(RegistrationRequest registrationRequest) {
        log.info("Attempting registration for username: {}", registrationRequest.username());

        // 1. Validate Password Confirmation
        if (!registrationRequest.password().equals(registrationRequest.passwordConfirmation())) {
            log.warn("Registration failed for {}: Passwords do not match", registrationRequest.username());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match");
        }

        // 2. Check for existing username
        if (userRepository.findByUsername(registrationRequest.username()).isPresent()) {
            log.warn("Registration failed: Username '{}' already exists.", registrationRequest.username());
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_USER_MESSAGE);
        }

        // 3. Check for existing email
        if (userRepository.findByEmail(registrationRequest.email()).isPresent()) {
            log.warn("Registration failed: Email '{}' already exists.", registrationRequest.email());
            throw new ResponseStatusException(HttpStatus.CONFLICT, DUPLICATE_USER_MESSAGE);
        }

        // 4. Hash the password securely
        String hashedPassword = passwordEncoder.encode(registrationRequest.password());

        // 5. Create the new user entity
        AppUser newUser = new AppUser(
                registrationRequest.username(),
                hashedPassword,
                DEFAULT_USER_ROLE,
                registrationRequest.email()
        );

        // 6. Generate Verification Token & Expiry
        String token = UUID.randomUUID().toString();
        Instant expiryDate = Instant.now().plus(Duration.parse(tokenDurationString));

        newUser.setVerificationToken(token);
        newUser.setVerificationTokenExpiryDate(expiryDate);
        newUser.setVerified(false); // Ensure it's false

        // 7. Save the user (including token info)
        AppUser savedUser = userRepository.save(newUser);
        log.info("Successfully registered user '{}' with ID: {}. Verification pending.", savedUser.getUsername(), savedUser.getId());

        // 8. Send verification email asynchronously
        try {
            emailService.sendVerificationEmail(savedUser, token, appBaseUrl);
        } catch (Exception e) {
            // Log error, but don't fail the registration transaction.
            // Should add a mechanism for the user to request resending the verification email later.
            log.error("Error triggering verification email for user {} after registration.", savedUser.getUsername(), e);
        }

        return savedUser; // Return the saved user (controller might ignore it)
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

        if (user.isVerified()) {
            log.warn("Verification attempt for already verified user: {}", user.getUsername());
            user.setVerificationToken(null);
            user.setVerificationTokenExpiryDate(null);
            userRepository.save(user);
            return true;
        }

        // Check expiry
        if (user.getVerificationTokenExpiryDate() == null || user.getVerificationTokenExpiryDate().isBefore(Instant.now())) {
            log.warn("Verification failed: Token expired for user: {}", user.getUsername());
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
}
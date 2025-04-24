package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.web.dto.RegistrationRequest;

/**
 * Service interface for managing user-related operations,
 * such as registration and verification.
 */
public interface UserService {

    /**
     * Registers a new user based on the provided registration details.
     * This typically involves validating the request, checking for duplicates,
     * hashing the password, saving the user, and potentially triggering
     * an email verification process.
     * If an unverified user with the same email already exists, it updates
     * the password and resends the verification email.
     *
     * @param registrationRequest DTO containing the new user's details (username, email, password, etc.).
     * @return The newly created or updated AppUser entity (potentially without sensitive info like password hash).
     * @throws org.springframework.web.server.ResponseStatusException if validation fails (e.g., duplicate username, duplicate email for *verified* user).
     */
    AppUser registerNewUser(RegistrationRequest registrationRequest);

    /**
     * Verifies a user's email address using the provided verification token.
     * Checks if the token is valid and not expired, then marks the user
     * as verified and invalidates the token.
     *
     * @param token The verification token received by the user (usually via email link).
     * @return true if the verification was successful, false otherwise (e.g., token invalid, expired, or not found).
     */
    boolean verifyUser(String token);

    /**
     * Requests a new verification email to be sent for the given email address.
     * If the email address exists and the user is not already verified,
     * generates a new token/expiry and sends the email.
     * Does nothing if the email is not found or the user is already verified.
     *
     * @param email The email address to resend verification for.
     */
    void resendVerificationEmail(String email);

}
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
     *
     * @param registrationRequest DTO containing the new user's details (username, email, password, etc.).
     * @return The newly created and saved AppUser entity (potentially without sensitive info like password hash).
     * @throws org.springframework.web.server.ResponseStatusException if validation fails (e.g., duplicate username/email, passwords mismatch).
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

}
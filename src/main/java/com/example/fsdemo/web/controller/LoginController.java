package com.example.fsdemo.web.controller;

import com.example.fsdemo.domain.AccountCredentials;
import com.example.fsdemo.security.JwtService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.util.HexFormat;

@RestController
public class LoginController {
    private static final Logger log = LoggerFactory.getLogger(LoginController.class);
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    private final SecureRandom secureRandom = new SecureRandom();

    public LoginController(JwtService jwtService, AuthenticationManager authenticationManager) {
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody AccountCredentials creds) {

        // 1. Authenticate user credentials
        var credentials = new UsernamePasswordAuthenticationToken(creds.username(), creds.password());
        Authentication authentication = authenticationManager.authenticate(credentials);
        String username = authentication.getName();
        log.info("Authentication successful for user: {}", username);

        // 2. Generate Fingerprint
        byte[] randomFgp = new byte[50]; // Generate 50 random bytes
        secureRandom.nextBytes(randomFgp);
        String userFingerprint = HexFormat.of().formatHex(randomFgp); // Use HexFormat

        // 3. Hash the Fingerprint for JWT claim
        String userFingerprintHash = JwtService.hashFingerprint(userFingerprint);
        if (userFingerprintHash == null) {
            // Handle hashing error - Internal Server Error
            log.error("Failed to hash fingerprint for user: {}", username);
            return ResponseEntity.internalServerError().body("Error processing login.");
        }

        // 4. Generate JWT with Fingerprint Hash
        String jwts = jwtService.generateToken(username, userFingerprintHash);

        // 5. Create Hardened Fingerprint Cookie using ResponseCookie builder
        // If and when introducing Max-Age, it must be less than or equal to JWT expiration.
        // Set it to JWT expiration for simplicity, or slightly less.

        ResponseCookie fingerprintCookie = ResponseCookie.from(JwtService.FINGERPRINT_COOKIE_NAME, userFingerprint)
                .httpOnly(true)     // Prevent access via JavaScript
                .secure(true)       // Only send over HTTPS
                .sameSite("Strict") // Prevent CSRF by not sending cookie on cross-site requests
                .path("/")          // Make cookie available for all paths
                .build();
        log.debug("Setting fingerprint cookie for user: {}", username);


        // 6. Build Response
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, JwtService.PREFIX + jwts)
                // Expose Authorization header to frontend JavaScript
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.AUTHORIZATION)
                // Add the Set-Cookie header generated by ResponseCookie
                .header(HttpHeaders.SET_COOKIE, fingerprintCookie.toString())
                .build();
    }
}

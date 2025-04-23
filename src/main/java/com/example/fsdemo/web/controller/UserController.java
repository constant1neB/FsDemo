package com.example.fsdemo.web.controller;

import com.example.fsdemo.domain.AccountCredentials;
import com.example.fsdemo.security.JwtService;
import com.example.fsdemo.service.UserService;
import com.example.fsdemo.web.dto.RegistrationRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/auth")
public class UserController {
    private static final Logger log = LoggerFactory.getLogger(UserController.class);
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserService userService;

    private final SecureRandom secureRandom = new SecureRandom();

    public UserController(JwtService jwtService, AuthenticationManager authenticationManager, UserService userService) {
        this.jwtService = jwtService;
        this.authenticationManager = authenticationManager;
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED) // Keep annotation for clarity
    public ResponseEntity<Object> registerUser(@Valid @RequestBody RegistrationRequest registrationRequest) {
        userService.registerNewUser(registrationRequest);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/verify-email")
    public ResponseEntity<String> verifyEmail(@RequestParam("token") String token) {
        boolean success = userService.verifyUser(token);

        if (success) {
            return ResponseEntity.ok("Email successfully verified! You can now log in.");
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired verification token.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<Object> login(@Valid @RequestBody AccountCredentials creds) {

        // 1. Authenticate user credentials (checks password and if verified via UserDetailsServiceImpl)
        var credentials = new UsernamePasswordAuthenticationToken(creds.username(), creds.password());
        Authentication authentication = authenticationManager.authenticate(credentials);
        String username = authentication.getName();
        log.info("Authentication successful for user: {}", username);

        // 2. Generate Fingerprint
        byte[] randomFgp = new byte[50];
        secureRandom.nextBytes(randomFgp);
        String userFingerprint = HexFormat.of().formatHex(randomFgp);

        // 3. Hash the Fingerprint for JWT claim
        String userFingerprintHash = JwtService.hashFingerprint(userFingerprint);

        // 4. Generate JWT with Fingerprint Hash
        String jwts = jwtService.generateToken(username, userFingerprintHash);

        // 5. Create Hardened Fingerprint Cookie
        ResponseCookie fingerprintCookie = ResponseCookie.from(JwtService.FINGERPRINT_COOKIE_NAME, userFingerprint)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api")
                .build();
        log.debug("Setting fingerprint cookie for user: {}", username);

        // 6. Build Response with JWT and Cookie
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, JwtService.PREFIX + jwts)
                .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, HttpHeaders.AUTHORIZATION)
                .header(HttpHeaders.SET_COOKIE, fingerprintCookie.toString())
                .build();
    }
}
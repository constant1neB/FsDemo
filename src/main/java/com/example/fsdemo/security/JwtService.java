package com.example.fsdemo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;

@Component
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    public static final String PREFIX = "Bearer ";
    public static final String FINGERPRINT_COOKIE_NAME = "__Secure-Fgp";
    public static final String FINGERPRINT_CLAIM = "fgpHash"; // Claim name for the fingerprint hash

    private SecretKey key;
    private final String base64Secret;
    private final long expirationTime;
    private final String issuer;

    public JwtService(
            @Value("${jwt.secret.key.base64}") String base64Secret,
            @Value("${jwt.expiration.ms:3600000}") long expirationTime, // Default 1 hour
            @Value("${jwt.issuer}") String issuer) {

        // Store the injected secret, validation happens in PostConstruct
        this.base64Secret = base64Secret;

        if (issuer == null || issuer.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT Issuer (jwt.issuer) must not be null or empty");
        }
        if (expirationTime <= 0) {
            throw new IllegalArgumentException("JWT Expiration time (jwt.expiration.ms) must be positive");
        }
        this.expirationTime = expirationTime;
        this.issuer = issuer;
        log.info("JWT Service Initializing. Issuer: {}, Expiration: {}ms", issuer, expirationTime);
    }

    @PostConstruct
    private void initializeKey() {
        log.debug("Attempting to initialize JWT Key from property/environment variable.");
        if (!StringUtils.hasText(this.base64Secret)) {
            log.error("CRITICAL: JWT Secret Key (jwt.secret.key.base64 or JWT_SECRET_KEY_BASE64 env var) is missing or empty!");
            throw new IllegalArgumentException("JWT Secret Key (jwt.secret.key.base64) must be provided via properties or environment variable (JWT_SECRET_KEY_BASE64)");
        }

        try {
            byte[] decodedKey = Base64.getDecoder().decode(this.base64Secret);
            if (decodedKey.length < 32) {
                log.error("CRITICAL: Provided JWT secret key is too short ({} bytes). Must be at least 256 bits (32 bytes).", decodedKey.length);
                throw new IllegalArgumentException("JWT Secret key must be at least 256 bits (32 bytes)");
            }
            this.key = Keys.hmacShaKeyFor(decodedKey);
            log.info("JWT Secret Key initialized successfully.");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Base64 encoding for JWT secret key (jwt.secret.key.base64)", e);
        }
    }

    /**
     * Generates a JWT including the SHA-256 hash of the user fingerprint.
     *
     * @param username            The subject of the token.
     * @param userFingerprintHash The SHA-256 hash of the fingerprint (hex encoded).
     * @return The generated JWT string.
     */
    public String generateToken(String username, String userFingerprintHash) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username must not be null or empty");
        }
        if (userFingerprintHash == null || userFingerprintHash.trim().isEmpty()) {
            throw new IllegalArgumentException("Fingerprint hash must not be null or empty");
        }
        Instant now = Instant.now();
        Instant expirationInstant = now.plus(Duration.ofMillis(expirationTime));

        String token = Jwts.builder()
                .subject(username)
                .issuer(issuer)
                .claim(FINGERPRINT_CLAIM, userFingerprintHash)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expirationInstant))
                .signWith(key)
                .compact();
        log.debug("Generated JWT for user '{}', expires at {}", username, expirationInstant);
        return token;
    }

    /**
     * Validates the JWT and the fingerprint hash against the provided cookie value.
     *
     * @param request The incoming HttpServletRequest.
     * @return The username (subject) if the token and fingerprint are valid, null otherwise.
     */
    public String validateTokenAndGetUsername(HttpServletRequest request) {
        Optional<String> tokenOpt = extractToken(request);
        Optional<String> fingerprintOpt = extractFingerprintCookie(request);

        if (tokenOpt.isEmpty() || fingerprintOpt.isEmpty()) {
            log.debug("JWT or Fingerprint cookie missing.");
            return null;
        }

        String token = tokenOpt.get();
        String userFingerprintFromCookie = fingerprintOpt.get();

        try {
            // 1. Verify JWT signature and standard claims (exp, iss)
            Jws<Claims> claimsJws = Jwts.parser()
                    .verifyWith(this.key)
                    .requireIssuer(this.issuer)
                    .build()
                    .parseSignedClaims(token);

            Claims claims = claimsJws.getPayload();

            // 2. Extract fingerprint hash from JWT claims
            String fingerprintHashFromToken = claims.get(FINGERPRINT_CLAIM, String.class);
            if (fingerprintHashFromToken == null || fingerprintHashFromToken.isBlank()) {
                log.warn("Fingerprint hash missing from JWT claims.");
                return null;
            }

            // 3. Hash the fingerprint from the cookie
            String calculatedFingerprintHash = hashFingerprint(userFingerprintFromCookie);

            // 4. Compare the hash from the token with the calculated hash from the cookie
            if (!MessageDigest.isEqual(
                    fingerprintHashFromToken.getBytes(StandardCharsets.UTF_8),
                    calculatedFingerprintHash.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Fingerprint mismatch.");
                return null;
            }

            // 5. Both JWT and fingerprint are valid
            String username = claims.getSubject();
            log.debug("JWT and Fingerprint validated successfully for user: {}", username);
            return username;

        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid token format or claim issue: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the raw JWT token from the Authorization header.
     */
    private Optional<String> extractToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            return Optional.of(header.substring(PREFIX.length()));
        }
        return Optional.empty();
    }

    /**
     * Extracts the user fingerprint value from the specific cookie.
     */
    private Optional<String> extractFingerprintCookie(HttpServletRequest request) {
        if (request.getCookies() != null) {
            return Arrays.stream(request.getCookies())
                    .filter(c -> FINGERPRINT_COOKIE_NAME.equals(c.getName()))
                    .map(Cookie::getValue)
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * Hashes the fingerprint string using SHA-256 and returns the hex representation.
     *
     * @param fingerprint The raw fingerprint string.
     * @return The hex encoded SHA-256 hash, or null if hashing fails.
     */
    public static String hashFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("Fingerprint must not be null or empty");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] userFingerprintDigest = digest.digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(userFingerprintDigest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 Algorithm not found!", e);
        }
    }
}
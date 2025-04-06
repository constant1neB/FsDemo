package com.example.fsdemo.service;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtService {
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    @Value("${jwt.expiration.ms:21600000}")
    private long expirationTime;
    static final String PREFIX = "Bearer";

    private final SecretKey key;

    public JwtService(@Value("${jwt.secret.key.base64}") String secret) {
        byte[] decodedKey = Base64.getDecoder().decode(secret);
        this.key = Keys.hmacShaKeyFor(decodedKey);
    }

    public String getToken(String username) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(Duration.ofMillis(expirationTime))))
                .signWith(key)
                .compact();
        return " " + token;
    }

    public String getAuthUser(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(PREFIX + " ")) { // Check prefix
            String token = header.substring(PREFIX.length() + 1).trim();
            try {
                return Jwts.parser()
                        .verifyWith(this.key)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload().
                        getSubject();
            } catch (JwtException e) {
                log.warn("JWT validation failed: {}", e.getMessage());
                return null;
            } catch (IllegalArgumentException e) {
                log.warn("Invalid token format: {}", e.getMessage());
                return null;
            }
        } else return null;
    }
}

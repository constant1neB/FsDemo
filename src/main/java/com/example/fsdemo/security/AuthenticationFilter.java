package com.example.fsdemo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);
    private final JwtService jwtService;

    public AuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        // Attempt to validate token and fingerprint cookie
        String username = jwtService.validateTokenAndGetUsername(request);

        if (username != null) {
            // Validation successful (JWT signature, claims, AND fingerprint match)
            Authentication authentication = new UsernamePasswordAuthenticationToken(
                    username,
                    null, // No credentials needed post-authentication
                    Collections.emptyList()); // Set authorities/roles if available/needed

            // Set authentication in the security context
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authentication successful for user '{}'. Security context updated.", username);
        } else {
            // Validation failed (missing token/cookie, invalid signature, claim mismatch, or fingerprint mismatch)
            // Clear context just in case, although it should be null anyway
            SecurityContextHolder.clearContext();
            log.debug("JWT/Fingerprint validation failed or not provided. Security context cleared.");
        }

        // Continue the filter chain regardless of authentication outcome here
        // Access control decisions happen later based on SecurityConfig and endpoint annotations
        filterChain.doFilter(request, response);
    }
}
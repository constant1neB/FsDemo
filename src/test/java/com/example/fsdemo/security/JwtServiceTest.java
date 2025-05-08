package com.example.fsdemo.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import javax.crypto.SecretKey;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JwtService Tests")
class JwtServiceTest {

    private JwtService jwtService;
    private final String base64Secret = Base64.getEncoder().encodeToString("TestSecretKeyMustBeAtLeast32BytesLongForHS256".getBytes());
    private final long expirationMs = 3600 * 1000; // 1 hour
    private final String issuer = "TestIssuer";
    private final String username = "testuser";
    private final String fingerprint = "user-specific-browser-fingerprint-string";
    private String fingerprintHash;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        fingerprintHash = JwtService.hashFingerprint(fingerprint);
        key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
        jwtService = new JwtService(base64Secret, expirationMs, issuer);
        initializeJwtService(jwtService);
    }

    private void initializeJwtService(JwtService service) {
        try {
            java.lang.reflect.Method initMethod = JwtService.class.getDeclaredMethod("initializeKey");
            initMethod.setAccessible(true);
            initMethod.invoke(service);
        } catch (Exception e) {
            if (e instanceof InvocationTargetException ite) {
                if (ite.getCause() instanceof RuntimeException re) {
                    throw re;
                } else if (ite.getCause() instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException("Failed to initialize key via reflection, unexpected cause", ite.getCause());
            }
            throw new RuntimeException("Failed to initialize key via reflection", e);
        }
    }


    @Nested
    @DisplayName("Initialization")
    class InitializationTests {
        @Test
        @DisplayName("Should initialize successfully with valid parameters")
        void initialization_Success() {
            assertThatCode(() -> {
                JwtService service = new JwtService(base64Secret, expirationMs, issuer);
                initializeJwtService(service);
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for missing secret during initialization")
        void initialization_FailMissingSecret() {
            JwtService service = new JwtService(null, expirationMs, issuer);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> initializeJwtService(service))
                    .withMessageContaining("JWT Secret Key (jwt.secret.key.base64) must be provided");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for blank secret during initialization")
        void initialization_FailBlankSecret() {
            JwtService service = new JwtService("   ", expirationMs, issuer);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> initializeJwtService(service))
                    .withMessageContaining("JWT Secret Key (jwt.secret.key.base64) must be provided");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for short secret after decode during initialization")
        void initialization_FailShortSecret() {
            String shortSecret = Base64.getEncoder().encodeToString("short".getBytes());
            JwtService service = new JwtService(shortSecret, expirationMs, issuer);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> initializeJwtService(service))
                    .withMessageContaining("JWT Secret key must be at least 256 bits (32 bytes)");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for invalid Base64 secret during initialization")
        void initialization_FailInvalidBase64() {
            JwtService service = new JwtService("Invalid Base64 $$$", expirationMs, issuer);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> initializeJwtService(service))
                    .withMessageContaining("Invalid Base64 encoding for JWT secret key");
        }


        @Test
        @DisplayName("Should throw IllegalArgumentException for missing issuer in constructor")
        void initialization_FailMissingIssuer() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JwtService(base64Secret, expirationMs, null))
                    .withMessageContaining("JWT Issuer (jwt.issuer) must not be null or empty");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for blank issuer in constructor")
        void initialization_FailBlankIssuer() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JwtService(base64Secret, expirationMs, "  "))
                    .withMessageContaining("JWT Issuer (jwt.issuer) must not be null or empty");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for non-positive expiration in constructor")
        void initialization_FailNonPositiveExpiration() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JwtService(base64Secret, 0, issuer))
                    .withMessageContaining("JWT Expiration time (jwt.expiration.ms) must be positive");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new JwtService(base64Secret, -1000, issuer))
                    .withMessageContaining("JWT Expiration time (jwt.expiration.ms) must be positive");
        }
    }


    @Nested
    @DisplayName("generateToken")
    class GenerateTokenTests {
        @Test
        @DisplayName("Should generate a valid JWT string")
        void generateToken_Success() {
            String token = jwtService.generateToken(username, fingerprintHash);

            assertThat(token).isNotNull().isNotEmpty();
            assertThat(token.split("\\.")).hasSize(3);

            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            assertThat(claims.getSubject()).isEqualTo(username);
            assertThat(claims.getIssuer()).isEqualTo(issuer);
            assertThat(claims.get(JwtService.FINGERPRINT_CLAIM, String.class)).isEqualTo(fingerprintHash);
            assertThat(claims.getIssuedAt()).isNotNull();
            assertThat(claims.getExpiration()).isNotNull();
            assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for null username")
        void generateToken_FailNullUsername() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> jwtService.generateToken(null, fingerprintHash))
                    .withMessage("Username must not be null or empty");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for blank username")
        void generateToken_FailBlankUsername() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> jwtService.generateToken(" ", fingerprintHash))
                    .withMessage("Username must not be null or empty");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for null fingerprint hash")
        void generateToken_FailNullFingerprintHash() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> jwtService.generateToken(username, null))
                    .withMessage("Fingerprint hash must not be null or empty");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for blank fingerprint hash")
        void generateToken_FailBlankFingerprintHash() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> jwtService.generateToken(username, "  "))
                    .withMessage("Fingerprint hash must not be null or empty");
        }
    }

    @Nested
    @DisplayName("validateTokenAndGetUsername")
    class ValidateTokenTests {
        MockHttpServletRequest request;
        String validToken;

        @BeforeEach
        void setupRequest() {
            request = new MockHttpServletRequest();
            validToken = jwtService.generateToken(username, fingerprintHash);
            request.addHeader("Authorization", JwtService.PREFIX + validToken);
            request.setCookies(new Cookie(JwtService.FINGERPRINT_COOKIE_NAME, fingerprint));
        }

        @Test
        @DisplayName("Should return username for valid token and matching fingerprint")
        void validate_Success() {
            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isEqualTo(username);
        }

        @Test
        @DisplayName("Should return null if Authorization header is missing")
        void validate_FailMissingHeader() {
            request.removeHeader("Authorization");
            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if Authorization header has wrong prefix")
        void validate_FailWrongPrefix() {
            request.removeHeader("Authorization");
            request.addHeader("Authorization", "InvalidPrefix " + validToken);
            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if token is malformed")
        void validate_FailMalformedToken() {
            request.removeHeader("Authorization");
            request.addHeader("Authorization", JwtService.PREFIX + "malformed.token.string");
            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }


        @Test
        @DisplayName("Should return null if fingerprint cookie is missing")
        void validate_FailMissingCookie() {
            request.setCookies();
            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if token signature is invalid")
        void validate_FailInvalidSignature() {
            SecretKey wrongKey = Keys.hmacShaKeyFor(Base64.getEncoder().encodeToString(
                    "DifferentSecretKeyForTestingSignatureFailure123".getBytes()).getBytes());
            String tokenWithWrongSig = Jwts.builder()
                    .subject(username)
                    .issuer(issuer)
                    .claim(JwtService.FINGERPRINT_CLAIM, fingerprintHash)
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plus(Duration.ofMillis(expirationMs))))
                    .signWith(wrongKey)
                    .compact();

            request.removeHeader("Authorization");
            request.addHeader("Authorization", JwtService.PREFIX + tokenWithWrongSig);

            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if token is expired")
        void validate_FailExpiredToken() {
            String expiredToken = Jwts.builder()
                    .subject(username)
                    .issuer(issuer)
                    .claim(JwtService.FINGERPRINT_CLAIM, fingerprintHash)
                    .issuedAt(Date.from(Instant.now().minus(Duration.ofHours(2))))
                    .expiration(Date.from(Instant.now().minus(Duration.ofHours(1))))
                    .signWith(key)
                    .compact();
            request.removeHeader("Authorization");
            request.addHeader("Authorization", JwtService.PREFIX + expiredToken);

            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if issuer is incorrect")
        void validate_FailWrongIssuer() {
            String tokenWithWrongIssuer = Jwts.builder()
                    .subject(username)
                    .issuer("WrongIssuer")
                    .claim(JwtService.FINGERPRINT_CLAIM, fingerprintHash)
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plus(Duration.ofMillis(expirationMs))))
                    .signWith(key)
                    .compact();
            request.removeHeader("Authorization");
            request.addHeader("Authorization", JwtService.PREFIX + tokenWithWrongIssuer);

            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if fingerprint claim is missing in token")
        void validate_FailMissingFingerprintClaim() {
            String tokenWithoutFingerprint = Jwts.builder()
                    .subject(username)
                    .issuer(issuer)
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plus(Duration.ofMillis(expirationMs))))
                    .signWith(key)
                    .compact();
            request.removeHeader("Authorization");
            request.addHeader("Authorization", JwtService.PREFIX + tokenWithoutFingerprint);

            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if fingerprint claim is blank in token")
        void validate_FailBlankFingerprintClaim() {
            String tokenWithBlankFingerprint = Jwts.builder()
                    .subject(username)
                    .issuer(issuer)
                    .claim(JwtService.FINGERPRINT_CLAIM, "  ")
                    .issuedAt(Date.from(Instant.now()))
                    .expiration(Date.from(Instant.now().plus(Duration.ofMillis(expirationMs))))
                    .signWith(key)
                    .compact();
            request.removeHeader("Authorization");
            request.addHeader("Authorization", JwtService.PREFIX + tokenWithBlankFingerprint);

            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }

        @Test
        @DisplayName("Should return null if fingerprint from cookie doesn't match hash in token")
        void validate_FailFingerprintMismatch() {
            request.setCookies(new Cookie(JwtService.FINGERPRINT_COOKIE_NAME, "different-fingerprint"));

            String resultUsername = jwtService.validateTokenAndGetUsername(request);
            assertThat(resultUsername).isNull();
        }
    }

    @Nested
    @DisplayName("hashFingerprint")
    class HashFingerprintTests {
        @Test
        @DisplayName("Should generate consistent SHA-256 hash")
        void hashFingerprint_Success() {
            String hash1 = JwtService.hashFingerprint(fingerprint);
            String hash2 = JwtService.hashFingerprint(fingerprint);

            assertThat(hash1)
                    .isNotNull()
                    .isNotEmpty()
                    .matches("^[a-f0-9]{64}$")
                    .isEqualTo(hash2); // Consistent hashing
        }

        @Test
        @DisplayName("Should generate different hashes for different inputs")
        void hashFingerprint_DifferentInputs() {
            String hash1 = JwtService.hashFingerprint("fingerprint1");
            String hash2 = JwtService.hashFingerprint("fingerprint2");
            assertThat(hash1).isNotEqualTo(hash2);
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for null input")
        void hashFingerprint_FailNull() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> JwtService.hashFingerprint(null))
                    .withMessage("Fingerprint must not be null or empty");
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for blank input")
        void hashFingerprint_FailBlank() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> JwtService.hashFingerprint("   "))
                    .withMessage("Fingerprint must not be null or empty");
        }
    }
}
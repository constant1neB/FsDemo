package com.example.fsdemo;

import com.example.fsdemo.domain.AppUser;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class AppUserValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void whenValidUser_thenNoViolations() {
        AppUser user = new AppUser("validuser", "ValidPass123", "USER", "test@example.com");
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).isEmpty();
    }

    // --- Username Tests ---
    @Test
    void whenUsernameIsNull_thenViolation() {
        AppUser user = new AppUser(null, "ValidPass123", "USER", "test@example.com");
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("username");
        assertThat(violations.iterator().next().getMessage()).contains("blank"); // Or specific message if set
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "}) // Blank strings
    void whenUsernameIsBlank_thenViolation(String blankUsername) {
        AppUser user = new AppUser(blankUsername, "ValidPass123", "USER", "test@example.com");
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(violation ->
                violation.getPropertyPath().toString().equals("username") && violation.getMessage().contains("blank"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us", "aVeryLongUsernameThatExceedsTheLimit"}) // Size constraints (assuming 3-20)
    void whenUsernameSizeIsInvalid_thenViolation(String invalidSizeUsername) {
        AppUser user = new AppUser(invalidSizeUsername, "ValidPass123", "USER", "test@example.com");
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("username");
        assertThat(violations.iterator().next().getMessage()).contains("size must be between");
    }

    // --- Password Tests --- (Assuming size constraint)
    @Test
    void whenPasswordIsNull_thenViolation() {
        AppUser user = new AppUser("validuser", null, "USER", "test@example.com");
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("password");
        assertThat(violations.iterator().next().getMessage()).contains("blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void whenPasswordIsBlank_thenViolation(String blankPassword) {
        AppUser user = new AppUser("validuser", blankPassword, "USER", "test@example.com");
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("password");
        assertThat(violations.iterator().next().getMessage()).contains("blank");
    }

    // --- Role Tests ---
    @Test
    void whenRoleIsNull_thenViolation() {
        AppUser user = new AppUser("validuser", "ValidPass123", null, "test@example.com");
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("role");
        assertThat(violations.iterator().next().getMessage()).contains("blank");
    }

    // --- Email Tests ---
    @Test
    void whenEmailIsNull_thenViolation() {
        // Note: Email is nullable in DB, but let's assume it's required for registration/update
        AppUser user = new AppUser("validuser", "ValidPass123", "USER", null);
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(violation -> violation.getPropertyPath().toString().equals("email"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void whenEmailIsBlank_thenViolation(String blankEmail) {
        AppUser user = new AppUser("validuser", "ValidPass123", "USER", blankEmail);
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email") && v.getMessage().contains("blank"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"plainaddress", "#@%^%#$@#$@#.com", "@example.com",
            "Joe Smith <email@example.com>", "email.example.com",
            "email@example@example.com", ".email@example.com",
            "email.@example.com", "email..email@example.com",
            "email@example.com (Joe Smith)", "email@example..com"})
    void whenEmailFormatIsInvalid_thenViolation(String invalidEmail) {
        AppUser user = new AppUser("validuser", "ValidPass123", "USER", invalidEmail);
        Set<ConstraintViolation<AppUser>> violations = validator.validate(user);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("email");
        assertThat(violations.iterator().next().getMessage()).contains("must be a well-formed email address");
    }
}
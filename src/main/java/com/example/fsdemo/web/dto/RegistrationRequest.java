package com.example.fsdemo.web.dto;

import jakarta.validation.constraints.*;

public record RegistrationRequest(
        @NotBlank(message = "Username cannot be blank")
        @Size(min = 3, max = 20, message = "Username size must be between 3 and 20 characters")
        @Pattern(regexp = "^\\w+$", message = "Username can only contain letters, numbers, and underscores")
        String username,

        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Email must be a well-formed email address")
        @Size(max = 255)
        String email,

        @NotBlank(message = "Password cannot be blank")
        @Size(min = 12, max = 70, message = "Password size must be between 12 and 70 characters")
        String password,

        @NotBlank(message = "Password confirmation cannot be blank")
        String passwordConfirmation
) {}
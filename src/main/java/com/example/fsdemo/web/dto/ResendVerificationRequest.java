package com.example.fsdemo.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResendVerificationRequest(
        @NotBlank(message = "Email cannot be blank")
        @Email(message = "Email must be a well-formed email address")
        @Size(max = 255)
        String email
) {}
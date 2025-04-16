package com.example.fsdemo.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateVideoRequest(
        @Size(max = 1024, message = "Description cannot exceed 1024 characters")
        @Pattern(regexp = "^[a-zA-Z0-9.,!?'\"\\-_;:() ]*$", message = "Description contains invalid characters. Only letters, numbers, spaces, and basic punctuation (. , ! ? ' \" - _ ; : ( )) are allowed.")
        String description
) {}
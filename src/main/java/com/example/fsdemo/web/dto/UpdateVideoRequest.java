package com.example.fsdemo.web.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateVideoRequest(
        @Size(max = 255, message = "Description cannot exceed 255 characters")
        @Pattern(regexp = "^[\\p{L}0-9.,!?_;:() \\r\\n-]*$", message = "Description contains invalid characters. Only letters, numbers, spaces, and basic punctuation (. , ! ? - _ ; : ( )) are allowed.")
        String description
) {}
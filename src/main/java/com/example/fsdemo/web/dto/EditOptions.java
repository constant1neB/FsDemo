package com.example.fsdemo.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record EditOptions(
        Double cutStartTime, // Optional: start time in seconds for cutting
        Double cutEndTime,   // Optional: end time in seconds for cutting
        @NotNull(message = "Mute flag must be provided")
        Boolean mute,        // Required: true to mute, false otherwise
        @Min(value = 144, message = "Resolution height must be at least 144") // Example validation
        Integer targetResolutionHeight // Optional: target vertical resolution (e.g., 480, 720, 1080)
) {}
package com.example.fsdemo.web.dto;

/**
 * Data Transfer Object (DTO) representing the response for video operations.
 * Contains information safe to expose to the client.
 */
public record VideoResponse(
        Long id,
        // The server-generated filename (UUID-based), NOT the user's original filename.
        String generatedFilename,
        String description,
        String ownerUsername,
        Long fileSize
) {}

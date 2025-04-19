package com.example.fsdemo.web.dto;

/**
 * Data Transfer Object (DTO) representing the response for video operations.
 * Contains information safe to expose to the client.
 */
public record VideoResponse(
        Long id,
        String description,
        String ownerUsername,
        Long fileSize
) {}

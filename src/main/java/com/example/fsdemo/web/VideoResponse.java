package com.example.fsdemo.web;

public record VideoResponse(
        Long id,
        String originalFilename,
        String description,
        String ownerUsername,
        Long fileSize
) {}

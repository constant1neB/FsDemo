package com.example.fsdemo.web.dto;

import org.springframework.core.io.Resource;

/**
 * Holds the necessary information for constructing a video download response.
 */
public record VideoDownloadDetails(
        Resource resource,
        String downloadFilename,
        String mimeType,
        Long contentLength
) {}
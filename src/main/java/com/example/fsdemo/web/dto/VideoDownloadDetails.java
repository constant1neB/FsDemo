package com.example.fsdemo.web.dto;

import org.springframework.core.io.Resource;

/**
 * Holds the necessary information for constructing a video download response.
 */
public record VideoDownloadDetails(
        Resource resource,
        String downloadFilename, // The filename to suggest to the client
        String mimeType,
        Long contentLength // Optional, helps set Content-Length header
) {}
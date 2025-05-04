package com.example.fsdemo.web.dto;

import com.example.fsdemo.domain.Video;

/**
 * Data Transfer Object for sending video status updates via Server-Sent Events.
 */
public record VideoStatusUpdateDto(
        String publicId,
        Video.VideoStatus status,
        String message
) {
    public VideoStatusUpdateDto(String publicId, Video.VideoStatus status) {
        this(publicId, status, null);
    }
}
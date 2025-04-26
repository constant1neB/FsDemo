package com.example.fsdemo.web.dto;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;

import java.time.Instant;

/**
 * Data Transfer Object (DTO) representing the response for video operations.
 * Contains information safe to expose to the client.
 */
public record VideoResponse(
        String publicId,
        String description,
        Long fileSize,
        VideoStatus status,
        Instant uploadDate,
        Double duration
) {

    /**
     * Static factory method to create a VideoResponse DTO from a Video entity.
     * Maps the entity's publicId to the DTO.
     *
     * @param video The Video entity.
     * @return A new VideoResponse instance.
     * @throws NullPointerException if video is null.
     */
    public static VideoResponse fromEntity(Video video) {
        if (video == null) {
            throw new NullPointerException("Cannot create VideoResponse from null Video entity");
        }
        return new VideoResponse(
                video.getPublicId(),
                video.getDescription(),
                video.getFileSize(),
                video.getStatus(),
                video.getUploadDate(),
                video.getDuration()
        );
    }
}
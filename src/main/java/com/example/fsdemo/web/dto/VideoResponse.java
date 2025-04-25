package com.example.fsdemo.web.dto;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;

import java.time.Instant;

/**
 * Data Transfer Object (DTO) representing the response for video operations.
 * Contains information safe to expose to the client.
 * UPDATED to include status, uploadDate, filename, duration
 */
public record VideoResponse(
        Long id,
        String description,
        Long fileSize,
        VideoStatus status,
        Instant uploadDate,
        String generatedFilename,
        Double duration
) {

    /**
     * Static factory method to create a VideoResponse DTO from a Video entity.
     * UPDATED to map new fields.
     *
     * @param video The Video entity.
     * @return A new VideoResponse instance.
     * @throws NullPointerException if video or video.getOwner() is null.
     */
    public static VideoResponse fromEntity(Video video) {
        if (video == null) {
            throw new NullPointerException("Cannot create VideoResponse from null Video entity");
        }
        if (video.getOwner() == null) {
            throw new NullPointerException("Cannot create VideoResponse from Video entity with null owner. Video ID: " + video.getId());
        }
        return new VideoResponse(
                video.getId(),
                video.getDescription(),
                video.getFileSize(),
                video.getStatus(),
                video.getUploadDate(),
                video.getGeneratedFilename(),
                video.getDuration()
        );
    }
}
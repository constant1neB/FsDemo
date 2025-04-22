package com.example.fsdemo.web.dto;

import com.example.fsdemo.domain.Video;

/**
 * Data Transfer Object (DTO) representing the response for video operations.
 * Contains information safe to expose to the client.
 */
public record VideoResponse(
        Long id,
        String description,
        String ownerUsername,
        Long fileSize
) {

    /**
     * Static factory method to create a VideoResponse DTO from a Video entity.
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
            // This shouldn't happen with proper data integrity, but good to check
            throw new NullPointerException("Cannot create VideoResponse from Video entity with null owner");
        }
        return new VideoResponse(
                video.getId(),
                video.getDescription(),
                video.getOwner().getUsername(),
                video.getFileSize()
        );
    }
}
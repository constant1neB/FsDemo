package com.example.fsdemo.web.dto;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("VideoResponse DTO Tests")
class VideoResponseTest {

    private AppUser owner;
    private Video video;

    @BeforeEach
    void setUp() {
        owner = new AppUser("testowner", "pass", "USER", "owner@test.com");
        owner.setId(1L);

        video = new Video(owner, "gen-name.mp4", "Test Desc",
                java.time.Instant.now(), "storage/path", 1024L, "video/mp4");
        video.setId(99L);
    }

    @Test
    @DisplayName("fromEntity should create correct DTO from valid Video")
    void fromEntity_Success() {
        VideoResponse dto = VideoResponse.fromEntity(video);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(video.getId());
        assertThat(dto.description()).isEqualTo(video.getDescription());
        assertThat(dto.ownerUsername()).isEqualTo(owner.getUsername());
        assertThat(dto.fileSize()).isEqualTo(video.getFileSize());
    }

    @Test
    @DisplayName("fromEntity should throw NullPointerException if Video is null")
    void fromEntity_FailNullVideo() {
        assertThatNullPointerException()
                .isThrownBy(() -> VideoResponse.fromEntity(null))
                .withMessageContaining("Cannot create VideoResponse from null Video entity");
    }

    @Test
    @DisplayName("fromEntity should throw NullPointerException if Video owner is null")
    void fromEntity_FailNullOwner() {
        // Create a video without setting the owner (or explicitly set to null)
        Video videoWithNullOwner = new Video(); // Use default constructor
        videoWithNullOwner.setId(100L);
        // owner field is null

        assertThatNullPointerException()
                .isThrownBy(() -> VideoResponse.fromEntity(videoWithNullOwner))
                .withMessageContaining("Cannot create VideoResponse from Video entity with null owner");
    }
}
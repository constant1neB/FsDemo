package com.example.fsdemo.web.dto;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("VideoResponse DTO Tests")
class VideoResponseTest {

    private Video video;
    private final String publicVideoId = "test-public-id-123";

    @BeforeEach
    void setUp() {
        AppUser owner = new AppUser("testOwner", "password", "USER", "owner@example.com");
        owner.setId(1L);

        video = new Video(owner, "Test Desc", Instant.now(), "path/gen-name.mp4", 12345L, "video/mp4");
        ReflectionTestUtils.setField(video, "id", 2L);
        ReflectionTestUtils.setField(video, "publicId", publicVideoId);
        video.setStatus(VideoStatus.READY);
        video.setDuration(120.5);
    }

    @Test
    @DisplayName("✅ fromEntity should correctly map Video entity to VideoResponse DTO")
    void fromEntity_MapsCorrectly() {
        VideoResponse dto = VideoResponse.fromEntity(video);

        assertThat(dto).isNotNull();
        assertThat(dto.publicId()).isEqualTo(publicVideoId);
        assertThat(dto.description()).isEqualTo(video.getDescription());
        assertThat(dto.fileSize()).isEqualTo(video.getFileSize());
        assertThat(dto.status()).isEqualTo(video.getStatus());
        assertThat(dto.uploadDate()).isEqualTo(video.getUploadDate());
        assertThat(dto.duration()).isEqualTo(video.getDuration());
    }

    @Test
    @DisplayName("❌ fromEntity should throw NullPointerException if Video entity is null")
    void fromEntity_NullVideo_ThrowsNullPointerException() {
        assertThatThrownBy(() -> VideoResponse.fromEntity(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Cannot create VideoResponse from null Video entity");
    }

    @Test
    @DisplayName("✅ fromEntity should handle null description and duration from Video entity")
    void fromEntity_HandlesNullFields() {
        video.setDescription(null);
        video.setDuration(null);

        VideoResponse dto = VideoResponse.fromEntity(video);

        assertThat(dto.description()).isNull();
        assertThat(dto.duration()).isNull();
        assertThat(dto.publicId()).isEqualTo(publicVideoId);
        assertThat(dto.fileSize()).isEqualTo(video.getFileSize());
    }
}
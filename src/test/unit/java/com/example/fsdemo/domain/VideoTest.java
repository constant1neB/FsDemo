package com.example.fsdemo.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Video Domain Tests")
class VideoTest {

    @Test
    @DisplayName("Default constructor should create an instance with default status")
    void defaultConstructor() {
        Video video = new Video();
        assertThat(video).isNotNull();
        assertThat(video.getStatus()).isEqualTo(Video.VideoStatus.UPLOADED);
    }

    @Test
    @DisplayName("Parameterized constructor should set fields correctly")
    void parameterizedConstructor() {
        AppUser owner = new AppUser("owner", "pass", "USER", "owner@example.com");
        owner.setId(1L);
        String desc = "Test Description";
        Instant now = Instant.now();
        String storagePath = "storage/video-uuid.mp4";
        Long size = 1024L;
        String mime = "video/mp4";

        Video video = new Video(owner, desc, now, storagePath, size, mime);

        assertThat(video.getOwner()).isEqualTo(owner);
        assertThat(video.getDescription()).isEqualTo(desc);
        assertThat(video.getUploadDate()).isEqualTo(now);
        assertThat(video.getStoragePath()).isEqualTo(storagePath);
        assertThat(video.getFileSize()).isEqualTo(size);
        assertThat(video.getMimeType()).isEqualTo(mime);
        assertThat(video.getStatus()).isEqualTo(Video.VideoStatus.UPLOADED);
        assertThat(video.getPublicId()).isNotNull().isNotEmpty();
        assertThat(video.getId()).isNull();
        assertThat(video.getProcessedStoragePath()).isNull();
        assertThat(video.getDuration()).isNull();
    }

    @Test
    @DisplayName("Setters should update field values")
    void settersUpdateValues() {
        Video video = new Video();
        Long id = 99L;
        String desc = "Updated Description";
        String processedPath = "processed/video.mp4";
        Double duration = 15.5;
        Video.VideoStatus status = Video.VideoStatus.READY;

        video.setId(id);
        video.setDescription(desc);
        video.setProcessedStoragePath(processedPath);
        video.setDuration(duration);
        video.setStatus(status);

        assertThat(video.getId()).isEqualTo(id);
        assertThat(video.getDescription()).isEqualTo(desc);
        assertThat(video.getProcessedStoragePath()).isEqualTo(processedPath);
        assertThat(video.getDuration()).isEqualTo(duration);
        assertThat(video.getStatus()).isEqualTo(status);
    }
}
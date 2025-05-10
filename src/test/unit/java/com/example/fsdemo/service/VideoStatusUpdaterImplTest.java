package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.events.VideoStatusChangedEvent;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.impl.VideoStatusUpdaterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoStatusUpdaterImpl Tests")
class VideoStatusUpdaterImplTest {

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private VideoStorageService videoStorageService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private VideoStatusUpdaterImpl statusUpdater;

    private Video testVideo;
    private final Long videoId = 1L;
    private final String publicVideoId = "public-uuid-123";
    private final String username = "testUser";
    private final String processedPath = "processed/proc.mp4";

    @BeforeEach
    void setUp() {
        AppUser testUser = new AppUser(username, "pass", "USER", "user@example.com");
        testUser.setId(10L);

        String originalPath = "originals/orig.mp4";
        testVideo = new Video(testUser, "Test Video Description", Instant.now(), originalPath, 100L, "video/mp4");
        ReflectionTestUtils.setField(testVideo, "id", videoId);
        ReflectionTestUtils.setField(testVideo, "publicId", publicVideoId);
    }

    @ParameterizedTest
    @EnumSource(value = VideoStatus.class, names = {"UPLOADED", "READY", "FAILED"})
    @DisplayName("✅ updateStatusToProcessing: Success from valid states")
    void updateStatusToProcessing_SuccessFromValidStates(VideoStatus initialState) {
        testVideo.setStatus(initialState);
        if (initialState == VideoStatus.READY) {
            testVideo.setProcessedStoragePath(processedPath);
        }
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoRepository.save(any(Video.class))).willAnswer(inv -> inv.getArgument(0));

        statusUpdater.updateStatusToProcessing(videoId);

        then(videoRepository).should().save(testVideo);
        assertThat(testVideo.getStatus()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(testVideo.getProcessedStoragePath()).isNull(); // Should be cleared

        if (initialState == VideoStatus.READY) {
            then(videoStorageService).should().delete(processedPath); // Verify cleanup
        } else {
            then(videoStorageService).should(never()).delete(anyString());
        }
        verifyEventPublished(VideoStatus.PROCESSING, null);
    }

    @Test
    @DisplayName("✅ updateStatusToProcessing: Idempotent if already PROCESSING")
    void updateStatusToProcessing_AlreadyProcessing() {
        testVideo.setStatus(VideoStatus.PROCESSING);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));

        assertThatCode(() -> statusUpdater.updateStatusToProcessing(videoId))
                .doesNotThrowAnyException();

        then(videoRepository).should(never()).save(any(Video.class));
        then(videoStorageService).should(never()).delete(anyString());
        assertThat(testVideo.getStatus()).isEqualTo(VideoStatus.PROCESSING);
        verifyEventPublished(VideoStatus.PROCESSING, null);
    }

    @Test
    @DisplayName("❌ updateStatusToProcessing: Fails if video not found")
    void updateStatusToProcessing_VideoNotFound() {
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> statusUpdater.updateStatusToProcessing(videoId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Video not found: " + videoId);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("⚠️ updateStatusToProcessing: Handles VideoStorageException during cleanup gracefully")
    void updateStatusToProcessing_StorageExceptionDuringCleanup() {
        testVideo.setStatus(VideoStatus.READY);
        testVideo.setProcessedStoragePath(processedPath);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoRepository.save(any(Video.class))).willAnswer(inv -> inv.getArgument(0));
        doThrow(new VideoStorageException("Cleanup failed")).when(videoStorageService).delete(processedPath);

        statusUpdater.updateStatusToProcessing(videoId);

        then(videoRepository).should().save(testVideo);
        assertThat(testVideo.getStatus()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(testVideo.getProcessedStoragePath()).isNull();
        verifyEventPublished(VideoStatus.PROCESSING, null);
    }

    @Test
    @DisplayName("✅ updateStatusToReady: Success")
    void updateStatusToReady_Success() {
        testVideo.setStatus(VideoStatus.PROCESSING);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoRepository.save(any(Video.class))).willAnswer(inv -> inv.getArgument(0));

        statusUpdater.updateStatusToReady(videoId, processedPath);

        then(videoRepository).should().save(testVideo);
        assertThat(testVideo.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(testVideo.getProcessedStoragePath()).isEqualTo(processedPath);
        verifyEventPublished(VideoStatus.READY, null);
    }

    @Test
    @DisplayName("❌ updateStatusToReady: Fails if video not found")
    void updateStatusToReady_VideoNotFound() {
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());
        assertThatThrownBy(() -> statusUpdater.updateStatusToReady(videoId, processedPath))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Video not found: " + videoId);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("❌ updateStatusToReady: Fails if processedPath is null or blank")
    void updateStatusToReady_NullOrBlankPath() {
        assertThatThrownBy(() -> statusUpdater.updateStatusToReady(videoId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Processed storage path is required");

        assertThatThrownBy(() -> statusUpdater.updateStatusToReady(videoId, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Processed storage path is required");
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @EnumSource(value = VideoStatus.class, names = {"UPLOADED", "READY", "FAILED"})
    @DisplayName("⚠️ updateStatusToReady: Warns if not in PROCESSING state but still updates")
    void updateStatusToReady_WarnsIfNotProcessingButUpdates(VideoStatus initialState) {
        testVideo.setStatus(initialState);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoRepository.save(any(Video.class))).willAnswer(inv -> inv.getArgument(0));

        statusUpdater.updateStatusToReady(videoId, processedPath);

        then(videoRepository).should().save(testVideo);
        assertThat(testVideo.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(testVideo.getProcessedStoragePath()).isEqualTo(processedPath);
        verifyEventPublished(VideoStatus.READY, null);
    }

    @Test
    @DisplayName("✅ updateStatusToFailed: Success if status was PROCESSING")
    void updateStatusToFailed_SuccessFromProcessing() {
        testVideo.setStatus(VideoStatus.PROCESSING);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoRepository.save(any(Video.class))).willAnswer(inv -> inv.getArgument(0));

        statusUpdater.updateStatusToFailed(videoId);

        then(videoRepository).should().save(testVideo);
        assertThat(testVideo.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(testVideo.getProcessedStoragePath()).isNull();
        verifyEventPublished(VideoStatus.FAILED, "Video processing failed.");
    }

    @ParameterizedTest
    @EnumSource(value = VideoStatus.class, names = {"UPLOADED", "READY", "FAILED"})
    @DisplayName("⚠️ updateStatusToFailed: No-op if status was not PROCESSING")
    void updateStatusToFailed_NoOpIfNotProcessing(VideoStatus initialState) {
        testVideo.setStatus(initialState);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));

        statusUpdater.updateStatusToFailed(videoId);

        then(videoRepository).should(never()).save(any(Video.class));
        assertThat(testVideo.getStatus()).isEqualTo(initialState);
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("⚠️ updateStatusToFailed: No-op if video not found, logs error")
    void updateStatusToFailed_VideoNotFound() {
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());
        statusUpdater.updateStatusToFailed(videoId);
        then(videoRepository).should(never()).save(any(Video.class));
        then(eventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("⚠️ updateStatusToFailed: Handles DB save exception gracefully, logs error")
    void updateStatusToFailed_DbSaveException() {
        testVideo.setStatus(VideoStatus.PROCESSING);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        doThrow(new RuntimeException("DB save failed")).when(videoRepository).save(any(Video.class));

        assertThatCode(() -> statusUpdater.updateStatusToFailed(videoId))
                .doesNotThrowAnyException();

        then(eventPublisher).shouldHaveNoInteractions();
    }

    private void verifyEventPublished(VideoStatus expectedStatus, String expectedMessage) {
        ArgumentCaptor<VideoStatusChangedEvent> eventCaptor = ArgumentCaptor.forClass(VideoStatusChangedEvent.class);
        then(eventPublisher).should().publishEvent(eventCaptor.capture());
        VideoStatusChangedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.getPublicId()).isEqualTo(publicVideoId);
        assertThat(publishedEvent.getUsername()).isEqualTo(username);
        assertThat(publishedEvent.getNewStatus()).isEqualTo(expectedStatus);
        assertThat(publishedEvent.getMessage()).isEqualTo(expectedMessage);
    }
}
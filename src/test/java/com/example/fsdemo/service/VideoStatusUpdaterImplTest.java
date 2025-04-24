package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.impl.VideoStatusUpdaterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException; // Example DB exception

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoStatusUpdater Implementation Tests")
class VideoStatusUpdaterImplTest {

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private VideoStatusUpdaterImpl videoStatusUpdater;

    @Captor
    private ArgumentCaptor<Video> videoCaptor;

    private AppUser testUser;
    private final Long videoId = 1L;
    private final String processedPath = "processed/video.mp4";

    @BeforeEach
    void setUp() {
        testUser = new AppUser("test", "pass", "USER", "test@test.com");
        testUser.setId(1L); // Set ID for consistency
    }

    private Video createVideoWithStatus(VideoStatus status) {
        Video video = new Video(testUser, "uuid.mp4", "Desc", Instant.now(), "orig.mp4", 100L, "video/mp4");
        video.setId(videoId);
        video.setStatus(status);
        // Set processed path if appropriate for the initial state
        if (status == VideoStatus.READY) {
            video.setProcessedStoragePath("old_processed.mp4");
        }
        return video;
    }

    @Nested
    @DisplayName("updateStatusToProcessing Tests")
    class UpdateToProcessingTests {

        @Test
        @DisplayName("✅ Success: Should update from UPLOADED to PROCESSING and clear processed path")
        void updateToProcessing_FromUploaded_Success() {
            Video video = createVideoWithStatus(VideoStatus.UPLOADED);
            video.setProcessedStoragePath("should_be_cleared.mp4"); // Add path to check clearing
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoRepository.save(any(Video.class))).willReturn(video); // Return saved video

            videoStatusUpdater.updateStatusToProcessing(videoId);

            then(videoRepository).should().save(videoCaptor.capture());
            Video savedVideo = videoCaptor.getValue();
            assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.PROCESSING);
            assertThat(savedVideo.getProcessedStoragePath()).isNull(); // Verify path cleared
        }

        @Test
        @DisplayName("✅ Success: Should update from READY to PROCESSING and clear processed path")
        void updateToProcessing_FromReady_Success() {
            Video video = createVideoWithStatus(VideoStatus.READY); // Already has processed path
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoRepository.save(any(Video.class))).willReturn(video);

            videoStatusUpdater.updateStatusToProcessing(videoId);

            then(videoRepository).should().save(videoCaptor.capture());
            Video savedVideo = videoCaptor.getValue();
            assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.PROCESSING);
            assertThat(savedVideo.getProcessedStoragePath()).isNull(); // Verify path cleared
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalStateException if already PROCESSING")
        void updateToProcessing_FromProcessing_ThrowsException() {
            Video video = createVideoWithStatus(VideoStatus.PROCESSING);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToProcessing(videoId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("current state: PROCESSING");

            then(videoRepository).should(never()).save(any(Video.class));
        }


        @Test
        @DisplayName("❌ Failure: Should throw IllegalStateException if status is FAILED")
        void updateToProcessing_FromFailed_ThrowsException() {
            Video video = createVideoWithStatus(VideoStatus.FAILED);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToProcessing(videoId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("current state: FAILED");

            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalStateException if video not found")
        void updateToProcessing_VideoNotFound_ThrowsException() {
            given(videoRepository.findById(videoId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToProcessing(videoId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Video not found: " + videoId);

            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalStateException on DB save error")
        void updateToProcessing_DbError_ThrowsException() {
            Video video = createVideoWithStatus(VideoStatus.UPLOADED);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoRepository.save(any(Video.class))).willThrow(new OptimisticLockingFailureException("DB Error"));

            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToProcessing(videoId))
                    .isInstanceOf(IllegalStateException.class) // Service wraps DB exceptions
                    .hasMessageContaining("Failed to update video status to PROCESSING")
                    .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        }
    }

    @Nested
    @DisplayName("updateStatusToReady Tests")
    class UpdateToReadyTests {

        @Test
        @DisplayName("✅ Success: Should update from PROCESSING to READY and set processed path")
        void updateToReady_Success() {
            Video video = createVideoWithStatus(VideoStatus.PROCESSING);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoRepository.save(any(Video.class))).willReturn(video);

            videoStatusUpdater.updateStatusToReady(videoId, processedPath);

            then(videoRepository).should().save(videoCaptor.capture());
            Video savedVideo = videoCaptor.getValue();
            assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.READY);
            assertThat(savedVideo.getProcessedStoragePath()).isEqualTo(processedPath);
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalStateException if not in PROCESSING state")
        void updateToReady_FromNonProcessing_ThrowsException() {
            Video video = createVideoWithStatus(VideoStatus.UPLOADED); // Not PROCESSING
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToReady(videoId, processedPath))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Video is not in PROCESSING state");

            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalArgumentException if processed path is null")
        void updateToReady_NullPath_ThrowsException() {
            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToReady(videoId, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Processed storage path is required");

            then(videoRepository).should(never()).findById(anyLong());
            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalArgumentException if processed path is blank")
        void updateToReady_BlankPath_ThrowsException() {
            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToReady(videoId, "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Processed storage path is required");

            then(videoRepository).should(never()).findById(anyLong());
            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalStateException if video not found")
        void updateToReady_VideoNotFound_ThrowsException() {
            given(videoRepository.findById(videoId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToReady(videoId, processedPath))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Video not found: " + videoId);

            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Failure: Should throw IllegalStateException on DB save error")
        void updateToReady_DbError_ThrowsException() {
            Video video = createVideoWithStatus(VideoStatus.PROCESSING);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoRepository.save(any(Video.class))).willThrow(new OptimisticLockingFailureException("DB Error"));

            assertThatThrownBy(() -> videoStatusUpdater.updateStatusToReady(videoId, processedPath))
                    .isInstanceOf(IllegalStateException.class) // Service wraps DB exceptions
                    .hasMessageContaining("Failed to update video status to READY")
                    .hasCauseInstanceOf(OptimisticLockingFailureException.class);
        }
    }

    @Nested
    @DisplayName("updateStatusToFailed Tests")
    class UpdateToFailedTests {

        @Test
        @DisplayName("✅ Success: Should update from PROCESSING to FAILED and clear processed path")
        void updateToFailed_FromProcessing_Success() {
            Video video = createVideoWithStatus(VideoStatus.PROCESSING);
            video.setProcessedStoragePath("should_be_cleared.mp4");
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoRepository.save(any(Video.class))).willReturn(video);

            videoStatusUpdater.updateStatusToFailed(videoId);

            then(videoRepository).should().save(videoCaptor.capture());
            Video savedVideo = videoCaptor.getValue();
            assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.FAILED);
            assertThat(savedVideo.getProcessedStoragePath()).isNull();
        }

        @Test
        @DisplayName("✅ No-Op: Should not change status if already FAILED")
        void updateToFailed_FromFailed_NoOp() {
            Video video = createVideoWithStatus(VideoStatus.FAILED);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

            videoStatusUpdater.updateStatusToFailed(videoId);

            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("✅ No-Op: Should not change status if UPLOADED")
        void updateToFailed_FromUploaded_NoOp() {
            Video video = createVideoWithStatus(VideoStatus.UPLOADED);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

            videoStatusUpdater.updateStatusToFailed(videoId);

            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("✅ No-Op: Should not change status if READY")
        void updateToFailed_FromReady_NoOp() {
            Video video = createVideoWithStatus(VideoStatus.READY);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

            videoStatusUpdater.updateStatusToFailed(videoId);

            then(videoRepository).should(never()).save(any(Video.class));
        }


        @Test
        @DisplayName("✅ Log: Should log error but not throw if video not found")
        void updateToFailed_VideoNotFound_LogsError() {
            given(videoRepository.findById(videoId)).willReturn(Optional.empty());

            // Should not throw, just log (verification via logging framework or spy needed for full check)
            assertThatCode(() -> videoStatusUpdater.updateStatusToFailed(videoId))
                    .doesNotThrowAnyException();

            then(videoRepository).should(never()).save(any(Video.class));
        }

        @Test
        @DisplayName("✅ Log: Should log error but not throw on DB save error")
        void updateToFailed_DbError_LogsError() {
            Video video = createVideoWithStatus(VideoStatus.PROCESSING);
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoRepository.save(any(Video.class))).willThrow(new OptimisticLockingFailureException("DB Error"));

            // Should not throw, just log (verification via logging framework or spy needed for full check)
            assertThatCode(() -> videoStatusUpdater.updateStatusToFailed(videoId))
                    .doesNotThrowAnyException();

            // Verify save was attempted
            then(videoRepository).should().save(any(Video.class));
        }
    }
}
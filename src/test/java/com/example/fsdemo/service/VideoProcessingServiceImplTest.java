package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.impl.VideoProcessingServiceImpl;
import com.example.fsdemo.web.dto.EditOptions;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoProcessingServiceImpl Tests")
class VideoProcessingServiceImplTest {

    @Mock private VideoRepository videoRepository;
    @Mock private VideoStorageService videoStorageService;
    @Mock private VideoStatusUpdater videoStatusUpdater;
    @Mock private FfmpegService ffmpegService;

    private VideoProcessingServiceImpl videoProcessingService;

    @TempDir Path tempDir;
    private Path processedStorageDir;
    private Path temporaryFilesDir;
    private final Long videoId = 1L;
    private final String username = "testUser";
    private String originalStoragePath;
    private EditOptions validEditOptions;
    private FFmpegBuilder mockFfmpegBuilder;


    @BeforeEach
    void setUp() throws IOException {
        AppUser owner = new AppUser(username, "password", "USER", "user@example.com");
        owner.setId(100L);

        processedStorageDir = tempDir.resolve("test-processed-storage");
        temporaryFilesDir = tempDir.resolve("test-temporary-files");

        Files.createDirectories(processedStorageDir);
        Files.createDirectories(temporaryFilesDir);

        videoProcessingService = new VideoProcessingServiceImpl(
                videoRepository,
                videoStorageService,
                videoStatusUpdater,
                ffmpegService,
                processedStorageDir.toString(),
                temporaryFilesDir.toString()
        );
        ReflectionTestUtils.invokeMethod(videoProcessingService, "initialize"); // Call PostConstruct manually


        originalStoragePath = "originals/" + UUID.randomUUID() + ".mp4";
        Video video = new Video(owner, "Original Video For Processing", Instant.now(), originalStoragePath, 1024L * 1024L, "video/mp4");
        ReflectionTestUtils.setField(video, "id", videoId);

        validEditOptions = new EditOptions(10.0, 20.0, false, 720);
        mockFfmpegBuilder = mock(FFmpegBuilder.class);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        Resource mockResource = new ByteArrayResource("test video content".getBytes());
        given(videoStorageService.load(originalStoragePath)).willReturn(mockResource);
        given(ffmpegService.buildFfmpegCommand(any(Path.class), any(Path.class), any(EditOptions.class), anyLong(), anyString()))
                .willReturn(mockFfmpegBuilder);
    }

    @Test
    @DisplayName("✅ processVideoEdits: Success - updates status, moves file, cleans up temps")
    void processVideoEdits_Success() throws Exception {
        doNothing().when(ffmpegService).executeFfmpegJob(eq(mockFfmpegBuilder), eq(videoId), anyString());
        doNothing().when(videoStatusUpdater).updateStatusToReady(eq(videoId), anyString());

        videoProcessingService.processVideoEdits(videoId, validEditOptions, username);

        then(videoRepository).should().findById(videoId);
        then(videoStorageService).should().load(originalStoragePath);
        then(ffmpegService).should().buildFfmpegCommand(
                argThat(p -> p.startsWith(temporaryFilesDir) && p.getFileName().toString().startsWith("temp-in-")),
                argThat(p -> p.startsWith(temporaryFilesDir) && p.getFileName().toString().startsWith("temp-out-") && p.toString().endsWith(".mp4")),
                eq(validEditOptions),
                eq(videoId),
                anyString());
        then(ffmpegService).should().executeFfmpegJob(eq(mockFfmpegBuilder), eq(videoId), anyString());

        ArgumentCaptor<String> processedPathCaptor = ArgumentCaptor.forClass(String.class);
        then(videoStatusUpdater).should().updateStatusToReady(eq(videoId), processedPathCaptor.capture());
        String capturedRelativePath = processedPathCaptor.getValue();
        String processedSubdirNameForTest = "processed";
        assertThat(capturedRelativePath).startsWith(processedSubdirNameForTest + "/processed-").endsWith(".mp4");

        Path expectedFinalFileDir = processedStorageDir;
        try (Stream<Path> stream = Files.list(expectedFinalFileDir)) {
            assertThat(stream.anyMatch(p -> p.getFileName().toString().equals(Path.of(capturedRelativePath).getFileName().toString())))
                    .isTrue();
        }

        try (Stream<Path> stream = Files.list(temporaryFilesDir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith("temp-in-") || p.getFileName().toString().startsWith("temp-out-")))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("❌ processVideoEdits: VideoNotFound - throws ResponseStatusException")
    void processVideoEdits_VideoNotFound() throws Exception {
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, validEditOptions, username))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);

        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
        then(ffmpegService).should(never()).executeFfmpegJob(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: FfmpegProcessingException - updates status to FAILED, re-throws")
    void processVideoEdits_FfmpegFailure_UpdatesStatusToFailedAndThrows() throws Exception {
        FfmpegProcessingException simulatedException = new FfmpegProcessingException("ffmpeg error");
        doThrow(simulatedException).when(ffmpegService).executeFfmpegJob(eq(mockFfmpegBuilder), eq(videoId), anyString());

        ThrowingCallable processingCall = () -> videoProcessingService.processVideoEdits(videoId, validEditOptions, username);

        assertThatThrownBy(processingCall)
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessageContaining("Video processing failed for video ID " + videoId)
                .hasCause(simulatedException);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        try (Stream<Path> stream = Files.list(temporaryFilesDir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith("temp-in-") || p.getFileName().toString().startsWith("temp-out-")))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("❌ processVideoEdits: TimeoutException during FFmpeg - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEdits_Timeout_UpdatesStatusToFailedAndThrows() throws Exception {
        TimeoutException timeoutException = new TimeoutException("FFmpeg timed out");
        doThrow(timeoutException).when(ffmpegService).executeFfmpegJob(eq(mockFfmpegBuilder), eq(videoId), anyString());

        ThrowingCallable processingCall = () -> videoProcessingService.processVideoEdits(videoId, validEditOptions, username);

        assertThatThrownBy(processingCall)
                .isInstanceOf(FfmpegProcessingException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        try (Stream<Path> stream = Files.list(temporaryFilesDir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith("temp-in-") || p.getFileName().toString().startsWith("temp-out-")))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("❌ processVideoEdits: InterruptedException during FFmpeg - updates status to FAILED, re-throws as FfmpegProcessingException, sets interrupt flag")
    void processVideoEdits_Interrupted_UpdatesStatusToFailedAndThrows() throws Exception {
        InterruptedException interruptedException = new InterruptedException("FFmpeg interrupted");
        doThrow(interruptedException).when(ffmpegService).executeFfmpegJob(eq(mockFfmpegBuilder), eq(videoId), anyString());
        @SuppressWarnings("unused") // Indicate we are intentionally clearing the flag
        boolean clearedBeforeTest = Thread.interrupted();

        ThrowingCallable processingCall = () -> videoProcessingService.processVideoEdits(videoId, validEditOptions, username);

        assertThatThrownBy(processingCall)
                .isInstanceOf(FfmpegProcessingException.class)
                .hasCauseInstanceOf(InterruptedException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        assertThat(Thread.currentThread().isInterrupted()).as("Interrupt flag should be set after InterruptedException").isTrue();
        @SuppressWarnings("unused") // Indicate we are intentionally clearing the flag
        boolean clearedAfterTest = Thread.interrupted();

        try (Stream<Path> stream = Files.list(temporaryFilesDir)) {
            assertThat(stream.filter(p -> p.getFileName().toString().startsWith("temp-in-") || p.getFileName().toString().startsWith("temp-out-")))
                    .as("Temporary files should be cleaned up after interrupted exception")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("❌ processVideoEdits: IOException during temp file prep (load) - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEdits_IOExceptionOnLoad_UpdatesStatusToFailedAndThrows() throws Exception {
        VideoStorageException storageException = new VideoStorageException("Cannot load original");
        given(videoStorageService.load(originalStoragePath)).willThrow(storageException);

        ThrowingCallable processingCall = () -> videoProcessingService.processVideoEdits(videoId, validEditOptions, username);

        assertThatThrownBy(processingCall)
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessageContaining("Video processing failed due to file operation for video ID " + videoId)
                .hasCauseInstanceOf(VideoStorageException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        then(ffmpegService).should(never()).executeFfmpegJob(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: IOException during temp file prep (copy) - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEdits_IOExceptionOnCopy_UpdatesStatusToFailedAndThrows() throws Exception {
        Resource mockResourceThrowsOnInputStream = mock(Resource.class);
        given(mockResourceThrowsOnInputStream.exists()).willReturn(true);
        given(mockResourceThrowsOnInputStream.isReadable()).willReturn(true);
        given(mockResourceThrowsOnInputStream.getInputStream()).willThrow(new IOException("Simulated copy error"));
        given(videoStorageService.load(originalStoragePath)).willReturn(mockResourceThrowsOnInputStream);

        ThrowingCallable processingCall = () -> videoProcessingService.processVideoEdits(videoId, validEditOptions, username);

        assertThatThrownBy(processingCall)
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessageContaining("Video processing failed due to file operation for video ID " + videoId)
                .hasCauseInstanceOf(IOException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        then(ffmpegService).should(never()).executeFfmpegJob(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: Invalid EditOptions (end time before start time) - throws ResponseStatusException")
    void processVideoEdits_InvalidEditOptions_EndTimeBeforeStartTime() throws Exception {
        EditOptions invalidOptions = new EditOptions(20.0, 10.0, false, 720);

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, invalidOptions, username))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                .hasMessageContaining("Invalid cut times: End time (10.00) must be strictly greater than start time (20.00).");

        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
        then(ffmpegService).should(never()).executeFfmpegJob(any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: Null EditOptions - throws ResponseStatusException")
    void processVideoEdits_NullEditOptions() throws Exception {
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, null, username))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                .hasMessageContaining("EditOptions cannot be null.");

        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
        then(ffmpegService).should(never()).executeFfmpegJob(any(), anyLong(), anyString());
    }
}
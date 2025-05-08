package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.impl.VideoProcessingServiceImpl;
import com.example.fsdemo.web.dto.EditOptions;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
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
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoProcessingServiceImpl Tests")
class VideoProcessingServiceImplTest {

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private VideoStorageService videoStorageService;
    @Mock
    private VideoStatusUpdater videoStatusUpdater;
    @Mock
    private FfmpegService ffmpegService;
    @Mock
    private FFmpegBuilder fFmpegBuilder;

    private VideoProcessingServiceImpl videoProcessingService;

    @TempDir
    Path tempDirRoot;

    private Path testProcessedPath;
    private Path testTempPath;

    private Video testVideo;
    private EditOptions validOptions;
    private final Long videoId = 1L;
    private final String username = "testUser";
    private final String originalStoragePath = "original-uuid.mp4";
    private final byte[] fakeVideoContent = "fake video data".getBytes();
    private final byte[] fakeProcessedContent = "fake processed video data".getBytes();

    @Captor
    private ArgumentCaptor<Path> tempInputPathCaptor;
    @Captor
    private ArgumentCaptor<Path> tempOutputPathCaptor;

    @BeforeEach
    void setUp() throws IOException {
        testProcessedPath = tempDirRoot.resolve("test-processed-files");
        testTempPath = tempDirRoot.resolve("test-temporary-files");
        Files.createDirectories(testProcessedPath);
        Files.createDirectories(testTempPath);

        videoProcessingService = new VideoProcessingServiceImpl(
                videoRepository,
                videoStorageService,
                videoStatusUpdater,
                ffmpegService,
                testProcessedPath.toString(),
                testTempPath.toString()
        );
        ReflectionTestUtils.invokeMethod(videoProcessingService, "initialize");

        AppUser testUser = new AppUser(username, "password", "USER", "test@test.com");
        testVideo = new Video
                (testUser, "Test Desc", Instant.now(), originalStoragePath, 1024L, "video/mp4");
        testVideo.setId(videoId);
        testVideo.setStatus(Video.VideoStatus.UPLOADED);

        validOptions = new EditOptions(10.0, 20.0, false, 720);
    }

    @Test
    @DisplayName("✅ processVideoEdits: Success - updates status, moves file, cleans up temps")
    void processVideoEdits_Success() throws Exception {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(originalStoragePath)).willReturn(originalResource);

        given(ffmpegService.buildFfmpegCommand(
                tempInputPathCaptor.capture(),
                tempOutputPathCaptor.capture(),
                eq(validOptions),
                eq(videoId),
                nullable(String.class)))
                .willReturn(fFmpegBuilder);

        doAnswer((Answer<Void>) invocation -> {
            Path outputPath = tempOutputPathCaptor.getValue();
            Files.write(outputPath, fakeProcessedContent);
            System.out.println("Simulated FFmpeg output file creation at: " + outputPath);
            return null;
        }).when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(videoId), nullable(String.class));

        doNothing().when(videoStatusUpdater).updateStatusToReady(eq(videoId), anyString());

        videoProcessingService.processVideoEdits(videoId, validOptions, username);

        then(videoRepository).should().findById(videoId);
        then(videoStorageService).should().load(originalStoragePath);
        then(ffmpegService).should().buildFfmpegCommand(
                any(Path.class), any(Path.class), eq(validOptions), eq(videoId), nullable(String.class));
        then(ffmpegService).should().executeFfmpegJob(eq(fFmpegBuilder), eq(videoId), nullable(String.class));
        then(videoStatusUpdater).should().updateStatusToReady(eq(videoId),
                argThat(path -> path.startsWith("processed/") && path.endsWith(".mp4")));

        try (Stream<Path> processedFiles = Files.list(testProcessedPath)) {
            assertThat(processedFiles.count())
                    .as("Check one processed file exists")
                    .isEqualTo(1);
        }
        try (Stream<Path> tempFiles = Files.list(testTempPath)) {
            assertThat(tempFiles.count())
                    .as("Check temporary directory is empty")
                    .isZero();
        }
    }

    @Test
    @DisplayName("❌ processVideoEdits: VideoNotFound - throws ResponseStatusException")
    void processVideoEdits_VideoNotFound() {
        // Arrange
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, validOptions, username))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND)
                .hasMessageContaining("Video not found for processing: " + videoId);

        then(videoRepository).should().findById(videoId);
        then(videoStorageService).should(never()).load(anyString());
        then(ffmpegService).should(never()).buildFfmpegCommand(any(), any(), any(), any(), any());
        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
    }

    @Test
    @DisplayName("❌ processVideoEdits: FfmpegProcessingException - updates status to FAILED, re-throws")
    void processVideoEdits_FfmpegFailure_UpdatesStatusToFailedAndThrows() throws Exception {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        FfmpegProcessingException ffmpegException = new FfmpegProcessingException("FFmpeg error", 1, "stderr output");

        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(originalStoragePath)).willReturn(originalResource);
        given(ffmpegService.buildFfmpegCommand(
                any(Path.class), any(Path.class), eq(validOptions), eq(videoId), nullable(String.class)))
                .willReturn(fFmpegBuilder);
        doThrow(ffmpegException).when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(videoId), nullable(String.class));

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, validOptions, username))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessage("Video processing failed for video ID " + videoId)
                .cause().isSameAs(ffmpegException);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        try (Stream<Path> tempFiles = Files.list(testTempPath)) {
            assertThat(tempFiles.count()).isZero();
        }
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: TimeoutException during FFmpeg - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEdits_Timeout_UpdatesStatusToFailedAndThrows() throws Exception {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        TimeoutException timeoutException = new TimeoutException("FFmpeg timed out");

        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(originalStoragePath)).willReturn(originalResource);
        given(ffmpegService.buildFfmpegCommand(
                any(Path.class), any(Path.class), eq(validOptions), eq(videoId), nullable(String.class)))
                .willReturn(fFmpegBuilder);
        doThrow(timeoutException).when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(videoId), nullable(String.class));

        // Act & Assert
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, validOptions, username))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessage("Video processing failed for video ID " + videoId)
                .cause().isInstanceOf(TimeoutException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        try (Stream<Path> tempFiles = Files.list(testTempPath)) {
            assertThat(tempFiles.count()).isZero();
        }
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: InterruptedException during FFmpeg - updates status to FAILED, re-throws as FfmpegProcessingException, sets interrupt flag")
    void processVideoEdits_Interrupted_UpdatesStatusToFailedAndThrows() throws Exception {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        InterruptedException interruptedException = new InterruptedException("Processing interrupted");

        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(originalStoragePath)).willReturn(originalResource);
        given(ffmpegService.buildFfmpegCommand(
                any(Path.class), any(Path.class), eq(validOptions), eq(videoId), nullable(String.class)))
                .willReturn(fFmpegBuilder);
        doThrow(interruptedException).when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(videoId), nullable(String.class));

        //noinspection ResultOfMethodCallIgnored
        Thread.interrupted();
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, validOptions, username))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessage("Video processing failed for video ID " + videoId)
                .cause().isInstanceOf(InterruptedException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        try (Stream<Path> tempFiles = Files.list(testTempPath)) {
            assertThat(tempFiles.count()).isZero();
        }
        assertThat(Thread.currentThread().isInterrupted())
                .as("Check interrupt flag is set after exception")
                .isTrue();

        //noinspection ResultOfMethodCallIgnored
        Thread.interrupted();
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: IOException during temp file prep (load) - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEdits_IOExceptionOnLoad_UpdatesStatusToFailedAndThrows() throws Exception {
        VideoStorageException loadException = new VideoStorageException("Cannot load original");
        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(originalStoragePath)).willThrow(loadException);

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, validOptions, username))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessageContaining("Video processing failed due to file operation for video ID " + videoId)
                .cause().isInstanceOf(VideoStorageException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        then(ffmpegService).should(never()).buildFfmpegCommand(any(), any(), any(), any(), any());
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: IOException during temp file prep (copy) - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEdits_IOExceptionOnCopy_UpdatesStatusToFailedAndThrows() throws Exception {
        Resource originalResource = Mockito.spy(new ByteArrayResource(fakeVideoContent));
        IOException copyException = new IOException("Simulated copy error");

        given(videoRepository.findById(videoId)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(originalStoragePath)).willReturn(originalResource);
        doThrow(copyException).when(originalResource).getInputStream();

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, validOptions, username))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessageContaining("Video processing failed due to file operation for video ID " + videoId)
                .cause().isInstanceOf(IOException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(videoId);
        then(ffmpegService).should(never()).buildFfmpegCommand(any(), any(), any(), any(), any());
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: Invalid EditOptions (end time before start time) - throws ResponseStatusException")
    void processVideoEdits_InvalidEditOptions_EndTimeBeforeStartTime() {
        EditOptions invalidOptions = new EditOptions(20.0, 10.0, false, 720);

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, invalidOptions, username))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST)
                .extracting(ex -> ((ResponseStatusException) ex).getReason())
                .asString()
                .contains("Invalid cut times");

        then(videoRepository).should(never()).findById(anyLong());
        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
    }

    @Test
    @DisplayName("❌ processVideoEdits: Null EditOptions - throws ResponseStatusException")
    void processVideoEdits_NullEditOptions() {
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, null, username))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue("statusCode", HttpStatus.BAD_REQUEST)
                .hasMessageContaining("EditOptions cannot be null");

        then(videoRepository).should(never()).findById(anyLong());
        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
    }
}
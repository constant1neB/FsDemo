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

    private static final Long VIDEO_ID = 1L;
    private static final String USERNAME = "testUser";
    private static final String ORIGINAL_STORAGE_PATH = "original-uuid.mp4";
    private static final String STATUS_MESSAGE = "statusCode";
    private static final String PROCESSING_FAILED_MESSAGE = "Video processing failed for video ID ";

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

        AppUser testUser = new AppUser(USERNAME, "password", "USER", "test@test.com");
        testVideo = new Video
                (testUser, "Test Desc", Instant.now(), ORIGINAL_STORAGE_PATH, 1024L, "video/mp4");
        testVideo.setId(VIDEO_ID);
        testVideo.setStatus(Video.VideoStatus.UPLOADED);

        validOptions = new EditOptions(10.0, 20.0, false, 720);
    }

    @Test
    @DisplayName("✅ processVideoEdits: Success - updates status, moves file, cleans up temps")
    void processVideoEditsSuccess() throws InterruptedException, TimeoutException, IOException {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(ORIGINAL_STORAGE_PATH)).willReturn(originalResource);

        given(ffmpegService.buildFfmpegCommand(
                tempInputPathCaptor.capture(),
                tempOutputPathCaptor.capture(),
                eq(validOptions),
                eq(VIDEO_ID),
                nullable(String.class)))
                .willReturn(fFmpegBuilder);

        doAnswer((Answer<Void>) invocation -> {
            Path outputPath = tempOutputPathCaptor.getValue();
            Files.write(outputPath, fakeProcessedContent);
            return null;
        }).when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(VIDEO_ID), nullable(String.class));

        doNothing().when(videoStatusUpdater).updateStatusToReady(eq(VIDEO_ID), anyString());

        videoProcessingService.processVideoEdits(VIDEO_ID, validOptions, USERNAME);

        then(videoRepository).should().findById(eq(VIDEO_ID));
        then(videoStorageService).should().load(eq(ORIGINAL_STORAGE_PATH));
        then(ffmpegService).should().buildFfmpegCommand(
                any(Path.class), any(Path.class), eq(validOptions), eq(VIDEO_ID), nullable(String.class));
        then(ffmpegService).should().executeFfmpegJob(eq(fFmpegBuilder), eq(VIDEO_ID), nullable(String.class));
        then(videoStatusUpdater).should().updateStatusToReady(eq(VIDEO_ID),
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
    void processVideoEditsVideoNotFound() {
        given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, validOptions, USERNAME))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue(STATUS_MESSAGE, HttpStatus.NOT_FOUND)
                .hasMessageContaining("Video not found for processing: " + VIDEO_ID);

        then(videoRepository).should().findById(eq(VIDEO_ID));
        then(videoStorageService).should(never()).load(anyString());
        then(ffmpegService).should(never()).buildFfmpegCommand(any(), any(), any(), any(), any());
        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
    }

    @Test
    @DisplayName("❌ processVideoEdits: FfmpegProcessingException - updates status to FAILED, re-throws")
    void processVideoEditsFfmpegFailureUpdatesStatusToFailedAndThrows() throws IOException, InterruptedException, TimeoutException {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        FfmpegProcessingException ffmpegException = new FfmpegProcessingException("FFmpeg error", 1, "stderr output");

        given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(ORIGINAL_STORAGE_PATH)).willReturn(originalResource);
        given(ffmpegService.buildFfmpegCommand(
                any(Path.class), any(Path.class), eq(validOptions), eq(VIDEO_ID), nullable(String.class)))
                .willReturn(fFmpegBuilder);
        doThrow(ffmpegException).when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(VIDEO_ID), nullable(String.class));

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, validOptions, USERNAME))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessage(PROCESSING_FAILED_MESSAGE + VIDEO_ID)
                .cause().isSameAs(ffmpegException);

        then(videoStatusUpdater).should().updateStatusToFailed(eq(VIDEO_ID));
        try (Stream<Path> tempFiles = Files.list(testTempPath)) {
            assertThat(tempFiles.count()).isZero();
        }
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    private void setupForFfmpegJobExecutionWithThrowable(Resource originalResource, Throwable throwable) throws TimeoutException, InterruptedException {
        given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(ORIGINAL_STORAGE_PATH)).willReturn(originalResource);
        given(ffmpegService.buildFfmpegCommand(
                any(Path.class), any(Path.class), eq(validOptions), eq(VIDEO_ID), nullable(String.class)))
                .willReturn(fFmpegBuilder);
        if (throwable != null) {
            doThrow(throwable).when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(VIDEO_ID), nullable(String.class));
        } else {
            doNothing().when(ffmpegService).executeFfmpegJob(eq(fFmpegBuilder), eq(VIDEO_ID), nullable(String.class));
        }
    }

    @Test
    @DisplayName("❌ processVideoEdits: TimeoutException during FFmpeg - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEditsTimeoutUpdatesStatusToFailedAndThrows() throws InterruptedException, TimeoutException, IOException {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        TimeoutException timeoutException = new TimeoutException("FFmpeg timed out");

        setupForFfmpegJobExecutionWithThrowable(originalResource, timeoutException);

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, validOptions, USERNAME))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessage(PROCESSING_FAILED_MESSAGE + VIDEO_ID)
                .cause().isInstanceOf(TimeoutException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(eq(VIDEO_ID));
        try (Stream<Path> tempFiles = Files.list(testTempPath)) {
            assertThat(tempFiles.count()).isZero();
        }
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }


    @Test
    @DisplayName("❌ processVideoEdits: InterruptedException during FFmpeg - updates status to FAILED, re-throws as FfmpegProcessingException, sets interrupt flag")
    void processVideoEditsInterruptedUpdatesStatusToFailedAndThrows() throws InterruptedException, TimeoutException, IOException {
        Resource originalResource = new ByteArrayResource(fakeVideoContent);
        InterruptedException interruptedException = new InterruptedException("Processing interrupted");

        setupForFfmpegJobExecutionWithThrowable(originalResource, interruptedException);

        //noinspection ResultOfMethodCallIgnored
        Thread.interrupted();
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, validOptions, USERNAME))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessage(PROCESSING_FAILED_MESSAGE + VIDEO_ID)
                .cause().isInstanceOf(InterruptedException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(eq(VIDEO_ID));
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
    void processVideoEditsIOExceptionOnLoadUpdatesStatusToFailedAndThrows() {
        VideoStorageException loadException = new VideoStorageException("Cannot load original");
        given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(ORIGINAL_STORAGE_PATH)).willThrow(loadException);

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, validOptions, USERNAME))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessageContaining("Video processing failed due to file operation for video ID " + VIDEO_ID)
                .cause().isInstanceOf(VideoStorageException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(eq(VIDEO_ID));
        then(ffmpegService).should(never()).buildFfmpegCommand(any(), any(), any(), any(), any());
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: IOException during temp file prep (copy) - updates status to FAILED, re-throws as FfmpegProcessingException")
    void processVideoEditsIOExceptionOnCopyUpdatesStatusToFailedAndThrows() throws IOException {
        Resource originalResource = Mockito.spy(new ByteArrayResource(fakeVideoContent));
        IOException copyException = new IOException("Simulated copy error");

        given(videoRepository.findById(VIDEO_ID)).willReturn(Optional.of(testVideo));
        given(videoStorageService.load(ORIGINAL_STORAGE_PATH)).willReturn(originalResource);
        doThrow(copyException).when(originalResource).getInputStream();

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, validOptions, USERNAME))
                .isInstanceOf(FfmpegProcessingException.class)
                .hasMessageContaining("Video processing failed due to file operation for video ID " + VIDEO_ID)
                .cause().isInstanceOf(IOException.class);

        then(videoStatusUpdater).should().updateStatusToFailed(eq(VIDEO_ID));
        then(ffmpegService).should(never()).buildFfmpegCommand(any(), any(), any(), any(), any());
        then(videoStatusUpdater).should(never()).updateStatusToReady(anyLong(), anyString());
    }

    @Test
    @DisplayName("❌ processVideoEdits: Invalid EditOptions (end time before start time) - throws ResponseStatusException")
    void processVideoEditsInvalidEditOptionsEndTimeBeforeStartTime() {
        EditOptions invalidOptions = new EditOptions(20.0, 10.0, false, 720); // end time < start time

        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, invalidOptions, USERNAME))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue(STATUS_MESSAGE, HttpStatus.BAD_REQUEST)
                .extracting(ex -> ((ResponseStatusException) ex).getReason())
                .asString()
                .contains("Invalid cut times");

        then(videoRepository).should(never()).findById(anyLong());
        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
    }

    @Test
    @DisplayName("❌ processVideoEdits: Null EditOptions - throws ResponseStatusException")
    void processVideoEditsNullEditOptions() {
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(VIDEO_ID, null, USERNAME))
                .isInstanceOf(ResponseStatusException.class)
                .hasFieldOrPropertyWithValue(STATUS_MESSAGE, HttpStatus.BAD_REQUEST)
                .hasMessageContaining("EditOptions cannot be null");

        then(videoRepository).should(never()).findById(anyLong());
        then(videoStatusUpdater).should(never()).updateStatusToFailed(anyLong());
    }
}
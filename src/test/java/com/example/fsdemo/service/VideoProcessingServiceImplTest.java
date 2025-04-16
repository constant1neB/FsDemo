package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.web.EditOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoProcessingServiceImplTest {

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private VideoStorageService videoStorageService;

    // Service under test - Manual Spy Creation
    private VideoProcessingServiceImpl videoProcessingService;

    @TempDir
    Path tempTestDir;

    private Path processedStorageLocation;
    private Path temporaryStorageLocation;
    private final String ffmpegPath = "ffmpeg_mock_path";

    // Test state variables
    private Long videoId;
    private String username;
    private Video video;
    private EditOptions defaultOptions;
    private final String originalStoragePath = "original/path/video.mp4";
    private Resource mockResource;

    @Captor
    private ArgumentCaptor<List<String>> commandCaptor;
    @Captor
    private ArgumentCaptor<Video> videoSaveCaptor;

    @BeforeEach
    void setUp() throws IOException {
        videoId = 1L;
        username = "testUser";
        defaultOptions = new EditOptions(null, null, false, 720);

        processedStorageLocation = tempTestDir.resolve("processed");
        temporaryStorageLocation = tempTestDir.resolve("temp");
        Files.createDirectories(processedStorageLocation);
        Files.createDirectories(temporaryStorageLocation);

        VideoProcessingServiceImpl realServiceInstance = new VideoProcessingServiceImpl(
                videoRepository,
                videoStorageService,
                processedStorageLocation.toString(),
                temporaryStorageLocation.toString(),
                ffmpegPath
        );

        videoProcessingService = Mockito.spy(realServiceInstance);

        // Setup Video Entity
        AppUser owner = new AppUser(username, "pass", "USER", "test@test.com");
        String generatedFilename = UUID.randomUUID() + ".mp4";
        video = new Video(owner, generatedFilename, "Test", Instant.now(), originalStoragePath, 1024L, "video/mp4");
        video.setId(videoId);
        video.setStatus(VideoStatus.PROCESSING);

        // Setup Mock Resource
        mockResource = new ByteArrayResource("mock video content".getBytes());

    }

    // Helper to find temp file
    private Path findTempFile(String prefix, String suffix) throws IOException {
        try (Stream<Path> stream = Files.list(temporaryStorageLocation)) {
            return stream.filter(p -> {
                        String name = p.getFileName().toString();
                        // Ensure it's a file, starts with prefix, ends with suffix
                        return Files.isRegularFile(p) && name.startsWith(prefix) && name.endsWith(suffix);
                    })
                    .findFirst()
                    .orElseThrow(() -> new FileNotFoundException(
                            "Temp file matching '" + prefix + "*'" + suffix + "' not found in " + temporaryStorageLocation));
        }
    }

    // --- Test Cases ---

    @Test
    void processVideoEdits_whenSuccess_shouldUpdateStatusAndMoveFileAndCleanup() throws Exception {
        // Arrange

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoStorageService.load(originalStoragePath)).willReturn(mockResource);
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commandCaptorForSuccess = ArgumentCaptor.forClass(List.class);
        doAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            String randomOutputPathStr = command.getLast();
            Path randomTempOutputPath = Paths.get(randomOutputPathStr);
            Files.createFile(randomTempOutputPath);
            System.out.println("[TEST STUB] Created expected random temp output file at: " + randomTempOutputPath);
            return null;
        }).when(videoProcessingService).executeFfmpegProcess(commandCaptorForSuccess.capture(), eq(videoId));

        // Act
        videoProcessingService.processVideoEdits(videoId, defaultOptions, username);

        // Assert
        // Verify interactions
        InOrder inOrder = Mockito.inOrder(videoRepository, videoStorageService, videoProcessingService);
        inOrder.verify(videoRepository).findById(videoId);
        inOrder.verify(videoStorageService).load(originalStoragePath);
        inOrder.verify(videoProcessingService).executeFfmpegProcess(eq(commandCaptorForSuccess.getValue()), eq(videoId));
        inOrder.verify(videoRepository).save(videoSaveCaptor.capture());

        // Verify Status and Path
        Video savedVideo = videoSaveCaptor.getValue();
        assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(savedVideo.getProcessedStoragePath()).isNotNull().endsWith(".mp4");

        // Verify File Operations
        Path finalPath = processedStorageLocation.resolve(savedVideo.getProcessedStoragePath());
        assertThat(finalPath).exists();

        // Temp dir should be empty after cleanup
        try (Stream<Path> stream = Files.list(temporaryStorageLocation)) {
            assertThat(stream.count()).isEqualTo(0);
        } catch (IOException e) {
            fail("Failed to list temporary directory for cleanup check", e);
        }
    }

    @Test
    void processVideoEdits_whenFfmpegFails_shouldUpdateStatusToFailedAndCleanup() throws Exception {
        // Arrange
        // --- Add necessary stubbings here ---
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoStorageService.load(originalStoragePath)).willReturn(mockResource);
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));
        // --- End stubbings ---

        RuntimeException ffmpegError = new RuntimeException("FFmpeg processing failed with exit code 1.");
        doThrow(ffmpegError).when(videoProcessingService).executeFfmpegProcess(anyList(), eq(videoId));

        // Act
        videoProcessingService.processVideoEdits(videoId, defaultOptions, username);

        // Assert
        then(videoRepository).should(times(2)).findById(videoId); // Initial + failure update
        then(videoStorageService).should().load(originalStoragePath);
        then(videoProcessingService).should().executeFfmpegProcess(anyList(), eq(videoId));
        then(videoRepository).should().save(videoSaveCaptor.capture());

        Video savedVideo = videoSaveCaptor.getValue();
        assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(savedVideo.getProcessedStoragePath()).isNull();

        assertThat(processedStorageLocation.toFile().list()).isEmpty();
        assertThat(Files.list(temporaryStorageLocation)).isEmpty();
    }

    @Test
    void processVideoEdits_whenTimeout_shouldUpdateStatusToFailedAndCleanup() throws Exception {
        // Arrange
        // --- Add necessary stubbings here ---
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoStorageService.load(originalStoragePath)).willReturn(mockResource);
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));
        // --- End stubbings ---

        TimeoutException timeoutError = new TimeoutException("FFmpeg process timed out");
        doThrow(timeoutError).when(videoProcessingService).executeFfmpegProcess(anyList(), eq(videoId));

        // Act
        videoProcessingService.processVideoEdits(videoId, defaultOptions, username);

        // Assert
        then(videoRepository).should(times(2)).findById(videoId); // Initial + failure
        then(videoStorageService).should().load(originalStoragePath);
        then(videoProcessingService).should().executeFfmpegProcess(anyList(), eq(videoId));
        then(videoRepository).should().save(videoSaveCaptor.capture());

        Video savedVideo = videoSaveCaptor.getValue();
        assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(savedVideo.getProcessedStoragePath()).isNull();

        assertThat(processedStorageLocation.toFile().list()).isEmpty();
        assertThat(Files.list(temporaryStorageLocation)).isEmpty();
    }

    @Test
    void processVideoEdits_whenStorageLoadFails_shouldUpdateStatusToFailed() throws Exception {
        // Arrange
        // --- Add necessary stubbings here ---
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video)); // Needed for failure update
        given(videoStorageService.load(originalStoragePath))
                .willThrow(new VideoStorageException("Cannot load resource"));
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0)); // Needed for failure update
        // --- End stubbings ---

        // Act
        videoProcessingService.processVideoEdits(videoId, defaultOptions, username);

        // Assert
        then(videoRepository).should(times(2)).findById(videoId); // Initial + failure update
        then(videoStorageService).should().load(originalStoragePath);
        then(videoProcessingService).should(never()).executeFfmpegProcess(anyList(), anyLong());
        then(videoRepository).should().save(videoSaveCaptor.capture());

        Video savedVideo = videoSaveCaptor.getValue();
        assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(savedVideo.getProcessedStoragePath()).isNull();
        assertThat(Files.list(temporaryStorageLocation)).isEmpty();
    }

    @Test
    void processVideoEdits_whenVideoNotFoundDuringExecution_shouldThrowAndNotSave() throws IOException, InterruptedException, TimeoutException {
        // Arrange
        // --- Add necessary stubbings here ---
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());
        // No other stubbings needed as it fails early
        // --- End stubbings ---

        // Act & Assert
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, defaultOptions, username))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Video not found for processing: " + videoId);

        then(videoRepository).should().findById(videoId);
        then(videoStorageService).should(never()).load(anyString());
        then(videoRepository).should(never()).save(any(Video.class));
        then(videoProcessingService).should(never()).executeFfmpegProcess(anyList(), anyLong());
    }

    @Test
    void processVideoEdits_whenVideoNotInProcessingState_shouldLogWarningAndNotProceed() throws Exception {
        // Arrange
        video.setStatus(VideoStatus.READY);
        // --- Add necessary stubbings here ---
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        // No other stubbings needed
        // --- End stubbings ---

        // Act
        videoProcessingService.processVideoEdits(videoId, defaultOptions, username);

        // Assert
        then(videoRepository).should().findById(videoId);
        then(videoStorageService).should(never()).load(anyString());
        then(videoProcessingService).should(never()).executeFfmpegProcess(anyList(), anyLong());
        then(videoRepository).should(never()).save(any(Video.class));
    }


    // --- Tests for buildFfmpegCommand (using ReflectionTestUtils) ---
    // These tests don't need the general stubbings, so they won't cause UnnecessaryStubbingException

    @Test
    void buildFfmpegCommand_withAllOptions_shouldGenerateCorrectArguments() throws Exception {
        // Arrange
        Path input = temporaryStorageLocation.resolve("input.mp4");
        Path output = temporaryStorageLocation.resolve("output.mp4");
        EditOptions options = new EditOptions(10.5, 55.0, true, 480);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) ReflectionTestUtils.invokeMethod(
                videoProcessingService, "buildFfmpegCommand", input, output, options);

        assertThat(command).containsExactly(
                ffmpegPath, "-y",
                "-ss", "10.5",
                "-i", input.toString(),
                "-to", "55.0",
                "-vf", "scale=-2:480",
                "-an",
                "-c:v", "libx265", "-tag:v", "hvc1", "-preset", "medium", "-crf", "23",
                output.toString()
        );
    }

    @Test
    void buildFfmpegCommand_withMuteFalse_shouldCopyAudio() throws Exception {
        Path input = temporaryStorageLocation.resolve("input.mp4");
        Path output = temporaryStorageLocation.resolve("output.mp4");
        EditOptions options = new EditOptions(null, null, false, null);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) ReflectionTestUtils.invokeMethod(
                videoProcessingService, "buildFfmpegCommand", input, output, options);

        assertThat(command)
                .doesNotContain("-an")
                .containsSubsequence("-c:a", "copy")
                .containsSubsequence("-c:v", "libx265", "-tag:v", "hvc1");
    }

    @Test
    void buildFfmpegCommand_withNoOptions_shouldGenerateBasicCommand() throws Exception {
        Path input = temporaryStorageLocation.resolve("input.mp4");
        Path output = temporaryStorageLocation.resolve("output.mp4");
        EditOptions options = new EditOptions(null, null, false, null);

        @SuppressWarnings("unchecked")
        List<String> command = (List<String>) ReflectionTestUtils.invokeMethod(
                videoProcessingService, "buildFfmpegCommand", input, output, options);

        assertThat(command).containsExactly(
                ffmpegPath, "-y",
                "-i", input.toString(),
                "-c:a", "copy",
                "-c:v", "libx265", "-tag:v", "hvc1", "-preset", "medium", "-crf", "23",
                output.toString()
        );
    }
}
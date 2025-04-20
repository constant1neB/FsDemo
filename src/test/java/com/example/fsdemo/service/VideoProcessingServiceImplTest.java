package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.impl.VideoProcessingServiceImpl;
import com.example.fsdemo.web.dto.EditOptions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // Lenient because some mocks might not be used in all failure paths
@DisplayName("VideoProcessingServiceImpl Integration Tests (Requires FFmpeg)")
class VideoProcessingServiceImplTest {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingServiceImplTest.class);

    private static final String FFMPEG_EXECUTABLE_NAME = "ffmpeg";
    private static final String SAMPLE_VIDEO_RESOURCE_NAME = "sample_video.mp4";
    private static final long DEFAULT_PROCESSING_TIMEOUT_SECONDS = 30;

    @Mock
    private VideoRepository videoRepository;
    @Mock
    private VideoStorageService videoStorageService;
    @Mock
    private VideoStatusUpdater videoStatusUpdater;


    // SUT - Instance under test
    private VideoProcessingServiceImpl videoProcessingService;

    @TempDir
    Path tempTestDir;

    private Path processedStorageLocation;
    private Path temporaryStorageLocation;
    private Path sampleVideoSourcePath;

    // Test state variables
    private Long videoId;
    private String username;
    private Video video;
    private EditOptions defaultOptions;
    private final String originalStoragePath = "original/db/path/video.mp4";


    @Captor
    private ArgumentCaptor<Video> videoSaveCaptor;

    // --- Test Setup ---

    @BeforeAll
    static void checkFfmpegExists() {
        boolean ffmpegFound = false;
        try {
            ProcessBuilder pb = new ProcessBuilder(FFMPEG_EXECUTABLE_NAME, "-version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (InputStream is = process.getInputStream()) {
                is.readAllBytes();
            }
            int exitCode = process.waitFor();
            ffmpegFound = (exitCode == 0);
            log.info("FFmpeg check exit code: {}", exitCode);
        } catch (IOException | InterruptedException e) {
            log.warn("FFmpeg check failed to execute: {}", e.getMessage());
        }
        final boolean finalFfmpegFound = ffmpegFound;
        assumeTrue(finalFfmpegFound, "Skipping tests: FFmpeg command '" + FFMPEG_EXECUTABLE_NAME + "' not found or failed to execute.");
    }

    @BeforeEach
    void setUp() throws IOException {
        videoId = 1L;
        username = "testUser";
        defaultOptions = new EditOptions(null, null, false, 720);

        processedStorageLocation = tempTestDir.resolve("processed");
        temporaryStorageLocation = tempTestDir.resolve("temp");
        Files.createDirectories(processedStorageLocation);
        Files.createDirectories(temporaryStorageLocation);

        ClassPathResource videoResource = new ClassPathResource(SAMPLE_VIDEO_RESOURCE_NAME);
        if (!videoResource.exists()) {
            throw new FileNotFoundException("Critical Test Setup Error: Sample video not found in resources: " + SAMPLE_VIDEO_RESOURCE_NAME + ". Tests cannot run.");
        }
        sampleVideoSourcePath = temporaryStorageLocation.resolve("source_" + UUID.randomUUID() + ".mp4");
        Files.copy(videoResource.getInputStream(), sampleVideoSourcePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied sample video to temporary source path: {}", sampleVideoSourcePath);


        videoProcessingService = new VideoProcessingServiceImpl(
                videoRepository,
                videoStorageService,
                videoStatusUpdater,
                processedStorageLocation.toString(),
                temporaryStorageLocation.toString(),
                FFMPEG_EXECUTABLE_NAME,
                DEFAULT_PROCESSING_TIMEOUT_SECONDS
        );

        AppUser owner = new AppUser(username, "pass", "USER", "test@test.com");
        String generatedFilename = UUID.randomUUID() + ".mp4";
        video = new Video(owner, generatedFilename, "Test", Instant.now(),
                originalStoragePath, Files.size(sampleVideoSourcePath), "video/mp4");
        video.setId(videoId);
        video.setStatus(VideoStatus.PROCESSING);

        Resource mockStorageResource = new ByteArrayResource(Files.readAllBytes(sampleVideoSourcePath));
        given(videoStorageService.load(originalStoragePath)).willReturn(mockStorageResource);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));

        cleanDirectory(temporaryStorageLocation, true);
        cleanDirectory(processedStorageLocation, false);
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanDirectory(temporaryStorageLocation, false);
        cleanDirectory(processedStorageLocation, false);
    }

    private void cleanDirectory(Path directory, boolean keepSource) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                files.forEach(file -> {
                    if (keepSource && file.equals(sampleVideoSourcePath)) {
                        return;
                    }
                    try {
                        Files.delete(file);
                    } catch (IOException e) {
                        log.error("Failed to delete temp file: {}", file, e);
                    }
                });
            }
        }
    }


    // ==============================================================
    // == Tests for processVideoEdits (Orchestration Logic)        ==
    // ==============================================================

    @Nested
    @DisplayName("processVideoEdits Orchestration Tests (Real FFmpeg)")
    class ProcessVideoEditsOrchestrationTests {

        @Test
        @DisplayName("✅ Success Flow: Should process video, update status, save path, move file, and cleanup temps")
        void processVideoEdits_SuccessFlow() {
            assertTimeoutPreemptively(Duration.ofSeconds(DEFAULT_PROCESSING_TIMEOUT_SECONDS + 10), () ->
                    videoProcessingService.processVideoEdits(videoId, defaultOptions, username),
                    "Video processing exceeded the expected time limit.");

            InOrder inOrder = Mockito.inOrder(videoRepository, videoStorageService);
            inOrder.verify(videoRepository).findById(videoId);
            inOrder.verify(videoStorageService).load(originalStoragePath);
            inOrder.verify(videoRepository).save(videoSaveCaptor.capture());

            Video savedVideo = videoSaveCaptor.getValue();
            assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.READY);
            assertThat(savedVideo.getProcessedStoragePath())
                    .isNotNull()
                    .startsWith(videoId + "-processed-")
                    .endsWith(".mp4");

            Path finalPath = processedStorageLocation.resolve(savedVideo.getProcessedStoragePath());
            assertThat(finalPath).exists().isNotEmptyFile();

            try (Stream<Path> stream = Files.list(temporaryStorageLocation)) {
                assertThat(stream.filter(p -> !p.equals(sampleVideoSourcePath)).count())
                        .as("Temporary directory should be empty except for the source file")
                        .isZero();
            } catch (IOException e) {
                fail("Failed to check temporary directory for cleanup", e);
            }
        }

        @Test
        @DisplayName("❌ Failure Flow (FFmpeg Error Simulation): Should call status updater and cleanup temps")
        void processVideoEdits_FfmpegFailureFlow() throws IOException, InterruptedException, TimeoutException {
            // Use a spy to simulate internal method failure
            VideoProcessingServiceImpl spiedService = Mockito.spy(videoProcessingService);
            FfmpegProcessingException simulatedException = new FfmpegProcessingException("Simulated FFmpeg Error", 1, "Error output");

            doThrow(simulatedException).when(spiedService).executeFfmpegProcess(anyList(), eq(videoId));
            // Ensure findById returns the video for the initial check
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    spiedService.processVideoEdits(videoId, defaultOptions, username),
                    "Processing logic (handling simulated error) took too long.");

            // Verify the flow
            then(videoRepository).should(times(1)).findById(videoId);
            then(videoStorageService).should().load(originalStoragePath); // Load attempt
            then(spiedService).should().executeFfmpegProcess(anyList(), eq(videoId)); // Simulated failure
            then(videoStatusUpdater).should().updateStatusToFailed(videoId);
            then(videoRepository).should(never()).save(any(Video.class));

            // Verify cleanup
            assertThat(processedStorageLocation).isEmptyDirectory();
            try (Stream<Path> stream = Files.list(temporaryStorageLocation)) {
                assertThat(stream.filter(p -> !p.equals(sampleVideoSourcePath)).count())
                        .as("Temporary directory should be empty except for the source file")
                        .isZero();
            } catch (IOException e) {
                fail("Failed to check temporary directory for cleanup", e);
            }
        }


        @Test
        @DisplayName("❌ Failure Flow (Storage Load Error): Should call status updater")
        void processVideoEdits_StorageLoadFailureFlow() {
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoStorageService.load(originalStoragePath))
                    .willThrow(new VideoStorageException("Cannot load resource"));

            videoProcessingService.processVideoEdits(videoId, defaultOptions, username);

            // Verify the flow
            then(videoRepository).should(times(1)).findById(videoId);
            then(videoStorageService).should().load(originalStoragePath);
            then(videoStatusUpdater).should().updateStatusToFailed(videoId);
            then(videoRepository).should(never()).save(any(Video.class));

            // Verify cleanup (temp files shouldn't have been created)
            try (Stream<Path> stream = Files.list(temporaryStorageLocation)) {
                assertThat(stream.filter(p -> !p.equals(sampleVideoSourcePath)).count())
                        .as("Temporary directory should be empty except for the source file")
                        .isZero();
            } catch (IOException e) {
                fail("Failed to check temp dir", e);
            }
        }
    }


    // ==============================================================
    // == Tests for executeFfmpegProcess (Direct Process Handling) ==
    // ==============================================================

    @Nested
    @DisplayName("executeFfmpegProcess Direct Tests (Real FFmpeg)")
    class ExecuteFfmpegProcessDirectTests {

        private Path testInputFile;
        private Path testOutputFile;

        @BeforeEach
        void setUpExecuteTests() {
            testInputFile = sampleVideoSourcePath;
            testOutputFile = temporaryStorageLocation.resolve("test_out_" + UUID.randomUUID() + ".mp4");
            assertThat(testOutputFile).doesNotExist();
        }

        @Test
        @DisplayName("✅ Should execute successfully with valid command and input")
        void executeFfmpegProcess_Success() {
            List<String> command = List.of(
                    FFMPEG_EXECUTABLE_NAME,
                    "-y",
                    "-i", testInputFile.toString(),
                    "-c", "copy",
                    testOutputFile.toString()
            );

            org.junit.jupiter.api.function.Executable ffmpegExecution =
                    () -> videoProcessingService.executeFfmpegProcess(command, videoId);
            assertTimeoutPreemptively(Duration.ofSeconds(DEFAULT_PROCESSING_TIMEOUT_SECONDS + 5), ffmpegExecution,
                    "FFmpeg simple copy command took too long.");

            assertThat(testOutputFile).exists().isNotEmptyFile();
        }

        @Test
        @DisplayName("❌ Should throw FfmpegProcessingException for invalid input file")
        void executeFfmpegProcess_FailureInvalidInput() {
            Path invalidInput = temporaryStorageLocation.resolve("non_existent_input_" + UUID.randomUUID() + ".mp4");
            List<String> command = List.of(
                    FFMPEG_EXECUTABLE_NAME,
                    "-y",
                    "-i", invalidInput.toString(),
                    "-c", "copy",
                    testOutputFile.toString()
            );

            ThrowingCallable ffmpegExecution = () -> videoProcessingService.executeFfmpegProcess(command, videoId);

            assertThatThrownBy(ffmpegExecution)
                    .isInstanceOf(FfmpegProcessingException.class)
                    .hasMessageContaining("FFmpeg processing failed with exit code")
                    .satisfies(ex -> {
                        FfmpegProcessingException fex = (FfmpegProcessingException) ex;
                        assertThat(fex.getExitCode()).as("Exit code should be non-zero").isNotZero();
                        assertThat(fex.getStderrOutput()).as("Stderr should indicate file not found")
                                .containsIgnoringCase("No such file or directory");
                        log.info("FFmpeg stderr on invalid input:\n{}", fex.getStderrOutput());
                    });

            assertThat(testOutputFile).doesNotExist();
        }


        @Test
        @DisplayName("❌ Should throw FfmpegProcessingException for invalid command option")
        void executeFfmpegProcess_FailureInvalidOption() {
            List<String> command = List.of(
                    FFMPEG_EXECUTABLE_NAME,
                    "-y",
                    "-i", testInputFile.toString(),
                    "-this-is-not-a-real-ffmpeg-option",
                    testOutputFile.toString()
            );

            ThrowingCallable ffmpegExecution = () -> videoProcessingService.executeFfmpegProcess(command, videoId);

            assertThatThrownBy(ffmpegExecution)
                    .isInstanceOf(FfmpegProcessingException.class)
                    .hasMessageContaining("FFmpeg processing failed with exit code")
                    .satisfies(ex -> {
                        FfmpegProcessingException fex = (FfmpegProcessingException) ex;
                        assertThat(fex.getExitCode()).isNotZero();
                        assertThat(fex.getStderrOutput()).as("Stderr should indicate unrecognized option")
                                .containsIgnoringCase("Unrecognized option");
                        log.info("FFmpeg stderr on invalid option:\n{}", fex.getStderrOutput());
                    });

            assertThat(testOutputFile).doesNotExist();
        }

        @Test
        @DisplayName("❌ Should throw TimeoutException when process exceeds timeout")
        @Tag("slow")
        void executeFfmpegProcess_Timeout() {
            long shortTimeoutSeconds = 1;
            VideoProcessingServiceImpl serviceWithShortTimeout = new VideoProcessingServiceImpl(
                    videoRepository, videoStorageService, videoStatusUpdater,
                    processedStorageLocation.toString(), temporaryStorageLocation.toString(),
                    FFMPEG_EXECUTABLE_NAME,
                    shortTimeoutSeconds
            );

            List<String> command = List.of(
                    FFMPEG_EXECUTABLE_NAME,
                    "-y",
                    "-f", "lavfi",
                    "-i", "testsrc=duration=5",
                    "-i", "-",
                    "-c", "copy",
                    "-f", "null",
                    "-"
            );
            log.info("Attempting timeout test with command that waits for stdin: {}", command);

            ThrowingCallable ffmpegExecution = () -> serviceWithShortTimeout.executeFfmpegProcess(command, videoId);

            assertTimeoutPreemptively(Duration.ofSeconds(shortTimeoutSeconds + 14), () -> { // JUnit timeout (15s total)
                assertThatThrownBy(ffmpegExecution)
                        .isInstanceOf(TimeoutException.class)
                        .hasMessageContaining("FFmpeg process timed out for video ID: " + videoId);
            }, "Test timeout exceeded while waiting for expected TimeoutException.");

            assertThat(testOutputFile).doesNotExist();
        }


        @Test
        @DisplayName("❌ Should throw IOException if ffmpeg executable path is invalid")
        void executeFfmpegProcess_InvalidPath() {
            String invalidPath = temporaryStorageLocation.resolve("non_existent_ffmpeg_command_" + UUID.randomUUID()).toString();
            VideoProcessingServiceImpl serviceWithInvalidPath = new VideoProcessingServiceImpl(
                    videoRepository, videoStorageService, videoStatusUpdater,
                    processedStorageLocation.toString(), temporaryStorageLocation.toString(),
                    invalidPath,
                    DEFAULT_PROCESSING_TIMEOUT_SECONDS
            );

            List<String> command = List.of(
                    invalidPath,
                    "-i", testInputFile.toString(),
                    testOutputFile.toString()
            );

            ThrowingCallable ffmpegExecution = () -> serviceWithInvalidPath.executeFfmpegProcess(command, videoId);

            assertThatThrownBy(ffmpegExecution)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("Cannot run program");

            assertThat(testOutputFile).doesNotExist();
        }
    }


    // ==============================================================
    // == Tests for buildFfmpegCommand (Helper Method)             ==
    // ==============================================================

    @Nested
    @DisplayName("buildFfmpegCommand Helper Tests")
    class BuildFfmpegCommandTests {

        @Test
        @DisplayName("✅ Should generate correct arguments with all options")
        void buildFfmpegCommand_withAllOptions() {
            Path input = temporaryStorageLocation.resolve("input.mp4");
            Path output = temporaryStorageLocation.resolve("output.mp4");
            EditOptions options = new EditOptions(10.5, 55.0, true, 480);

            List<String> command = ReflectionTestUtils.invokeMethod(
                    videoProcessingService, "buildFfmpegCommand", input, output, options);

            assertThat(command).containsExactly(
                    FFMPEG_EXECUTABLE_NAME,
                    "-y",
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
        @DisplayName("✅ Should copy audio when mute is false")
        void buildFfmpegCommand_withMuteFalse() {
            Path input = temporaryStorageLocation.resolve("input.mp4");
            Path output = temporaryStorageLocation.resolve("output.mp4");
            EditOptions options = new EditOptions(null, null, false, null);

            List<String> command = ReflectionTestUtils.invokeMethod(
                    videoProcessingService, "buildFfmpegCommand", input, output, options);

            assertThat(command)
                    .startsWith(FFMPEG_EXECUTABLE_NAME)
                    .doesNotContain("-an")
                    .containsSubsequence("-c:a", "copy");
        }

        @Test
        @DisplayName("✅ Should generate basic command with no optional edits")
        void buildFfmpegCommand_withNoOptions() {
            Path input = temporaryStorageLocation.resolve("input.mp4");
            Path output = temporaryStorageLocation.resolve("output.mp4");
            EditOptions options = new EditOptions(null, null, false, null);

            List<String> command = ReflectionTestUtils.invokeMethod(
                    videoProcessingService, "buildFfmpegCommand", input, output, options);

            assertThat(command)
                    .containsExactly(
                            FFMPEG_EXECUTABLE_NAME,
                            "-y",
                            "-i", input.toString(),
                            "-c:a", "copy",
                            "-c:v", "libx265", "-tag:v", "hvc1", "-preset", "medium", "-crf", "23",
                            output.toString()
                    )
                    .doesNotContain("-ss", "-to", "-vf", "-an");
        }
    }
}
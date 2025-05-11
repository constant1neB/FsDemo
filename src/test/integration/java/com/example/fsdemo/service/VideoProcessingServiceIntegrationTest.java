package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.web.dto.EditOptions;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;


import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VideoProcessingServiceIntegrationTest {

    @Autowired
    private VideoProcessingService videoProcessingService;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private VideoStatusUpdater videoStatusUpdater;

    @TempDir
    static Path sharedTempDir;

    private static Path originalsPath;
    private static Path processedPath;
    private static Path tempPath;

    private AppUser testUser;
    private Video testVideo;
    private EditOptions validOptions;
    private static final String SAMPLE_VIDEO_FILENAME = "sample.mp4";


    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        originalsPath = sharedTempDir.resolve("it-originals");
        processedPath = sharedTempDir.resolve("it-processed");
        tempPath = sharedTempDir.resolve("it-temp");

        registry.add("video.storage.path", originalsPath::toString);
        registry.add("video.storage.processed.path", processedPath::toString);
        registry.add("video.storage.temp.path", tempPath::toString);
        registry.add("ffmpeg.timeout.seconds", () -> "30");

        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.defer-datasource-initialization", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(originalsPath);
        Files.createDirectories(processedPath);
        Files.createDirectories(tempPath);

        testUser = appUserRepository.findByUsername("testUser").orElseGet(() ->
                appUserRepository.save(new AppUser("testUser", "password",
                        "USER", "testuser@example.com"))
        );
        if (!testUser.isVerified()) {
            testUser.setVerified(true);
            appUserRepository.save(testUser);
        }

        String uniqueOriginalFilename = "original-" + UUID.randomUUID() + ".mp4";
        Path targetOriginalFile = originalsPath.resolve(uniqueOriginalFilename);

        ClassPathResource sampleVideoResource = new ClassPathResource(SAMPLE_VIDEO_FILENAME);
        if (!sampleVideoResource.exists()) {
            throw new IOException("Test resource " + SAMPLE_VIDEO_FILENAME + " not found in classpath.");
        }

        long fileSize;
        try (InputStream sampleVideoStream = sampleVideoResource.getInputStream()) {
            Files.copy(sampleVideoStream, targetOriginalFile, StandardCopyOption.REPLACE_EXISTING);
            fileSize = Files.size(targetOriginalFile);
        }

        Video video = new Video(testUser, "Integration Test Desc", Instant.now(),
                uniqueOriginalFilename, fileSize, "video/mp4");
        video.setStatus(Video.VideoStatus.UPLOADED);
        testVideo = videoRepository.save(video);

        validOptions = new EditOptions(0.1, 0.5, false, 360);
    }

    @AfterEach
    void tearDown() throws IOException {
        videoRepository.deleteAll();
        deleteDirectoryContents(originalsPath);
        deleteDirectoryContents(processedPath);
        deleteDirectoryContents(tempPath);
    }

    private void deleteDirectoryContents(Path directory) throws IOException {
        if (Files.exists(directory) && Files.isDirectory(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .filter(p -> !p.equals(directory))
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                System.err.println("WARN: Failed to delete path during cleanup: " + p + " - " + e.getMessage());
                            }
                        });
            }
        }
    }


    @Test
    @Order(1)
    void processVideoEdits_Success() {
        Long currentVideoId = testVideo.getId();
        videoStatusUpdater.updateStatusToProcessing(currentVideoId);

        videoProcessingService.processVideoEdits(currentVideoId, validOptions, testUser.getUsername());

        Awaitility.await().atMost(35, TimeUnit.SECONDS).pollInterval(Duration.ofSeconds(1)).until(() -> {
            Optional<Video> optionalVideo = videoRepository.findById(currentVideoId);
            return optionalVideo.isPresent() && optionalVideo.get().getStatus() == Video.VideoStatus.READY;
        });

        Video updatedVideo = videoRepository.findById(currentVideoId).orElseThrow();
        assertThat(updatedVideo.getStatus()).isEqualTo(Video.VideoStatus.READY);
        assertThat(updatedVideo.getProcessedStoragePath()).isNotNull().startsWith("processed/processed-").endsWith(".mp4");

        Path finalProcessedFile = processedPath.resolve(updatedVideo.getProcessedStoragePath().substring("processed/".length()));
        assertThat(Files.exists(finalProcessedFile)).isTrue();

        try {
            assertThat(Files.size(finalProcessedFile)).isGreaterThan(0);
        } catch (IOException e) {
            fail("Failed to check size of processed file", e);
        }

        try (Stream<Path> tempFiles = Files.list(tempPath)) {
            assertThat(tempFiles.count())
                    .as("Check temporary directory is empty after success")
                    .isZero();
        } catch (IOException e) {
            fail("Failed to check temp directory", e);
        }
    }

    @Test
    @Order(2)
    void processVideoEdits_VideoNotFound() {
        Long nonExistentVideoId = testVideo.getId() + 999L;
        Long existingVideoId = testVideo.getId();

        videoProcessingService.processVideoEdits(nonExistentVideoId, validOptions, testUser.getUsername());

        Awaitility.await().pollDelay(Duration.ofSeconds(2)).until(() -> true);

        Video videoShouldBeUnchanged = videoRepository.findById(existingVideoId).orElseThrow();
        assertThat(videoShouldBeUnchanged.getStatus()).isEqualTo(Video.VideoStatus.UPLOADED);
        assertThat(videoShouldBeUnchanged.getProcessedStoragePath()).isNull();
    }


    @Test
    @Order(3)
    void processVideoEdits_FfmpegFailure() throws IOException {
        String badOriginalFilename = "bad-ffmpeg-input-" + UUID.randomUUID() + ".mp4";
        Path badSourceFile = originalsPath.resolve(badOriginalFilename);
        Files.write(badSourceFile, new byte[0]);

        Video badVideo = new Video(testUser, "Bad FFmpeg Test", Instant.now(), badOriginalFilename,
                0L, "video/mp4");
        Video savedBadVideo = videoRepository.save(badVideo);
        Long badVideoId = savedBadVideo.getId();

        videoStatusUpdater.updateStatusToProcessing(badVideoId);

        videoProcessingService.processVideoEdits(badVideoId, validOptions, testUser.getUsername());

        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofSeconds(1)).until(() -> {
            Optional<Video> optionalVideo = videoRepository.findById(badVideoId);
            return optionalVideo.isPresent() && optionalVideo.get().getStatus() == Video.VideoStatus.FAILED;
        });

        Video updatedVideo = videoRepository.findById(badVideoId).orElseThrow();
        assertThat(updatedVideo.getStatus()).isEqualTo(Video.VideoStatus.FAILED);
        assertThat(updatedVideo.getProcessedStoragePath()).isNull();

        try (Stream<Path> tempFiles = Files.list(tempPath)) {
            assertThat(tempFiles.count())
                    .as("Check temporary directory is empty after FFmpeg failure")
                    .isZero();
        } catch (IOException e) {
            fail("Failed to check temp directory", e);
        }
    }


    @Test
    @Order(4)
    void processVideoEdits_OriginalFileNotFoundInStorage() throws IOException {
        String missingFileStoragePath = "missing-original-" + UUID.randomUUID() + ".mp4";
        Video videoWithMissingFile = new Video(testUser, "Missing File Test", Instant.now(),
                missingFileStoragePath, 100L, "video/mp4");
        Video savedVideo = videoRepository.save(videoWithMissingFile);
        Long missingVideoId = savedVideo.getId();

        Files.deleteIfExists(originalsPath.resolve(missingFileStoragePath));

        videoStatusUpdater.updateStatusToProcessing(missingVideoId);

        videoProcessingService.processVideoEdits(missingVideoId, validOptions, testUser.getUsername());

        Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofSeconds(1)).until(() -> {
            Optional<Video> optionalVideo = videoRepository.findById(missingVideoId);
            return optionalVideo.isPresent() && optionalVideo.get().getStatus() == Video.VideoStatus.FAILED;
        });

        Video updatedVideo = videoRepository.findById(missingVideoId).orElseThrow();
        assertThat(updatedVideo.getStatus()).isEqualTo(Video.VideoStatus.FAILED);
        assertThat(updatedVideo.getProcessedStoragePath()).isNull();
    }

    @Test
    @Order(5)
    void processVideoEdits_InvalidEditOptions_EndTimeBeforeStartTime() {
        EditOptions invalidOptions = new EditOptions(20.0, 10.0, false, 720);
        Long currentVideoId = testVideo.getId();
        Video.VideoStatus initialStatus = testVideo.getStatus();

        videoProcessingService.processVideoEdits(currentVideoId, invalidOptions, testUser.getUsername());

        Awaitility.await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Video videoNotProcessed = videoRepository.findById(currentVideoId).orElseThrow();
            assertThat(videoNotProcessed.getStatus()).isEqualTo(initialStatus);
            assertThat(videoNotProcessed.getProcessedStoragePath()).isNull();
        });
    }

    @Test
    @Order(6)
    void processVideoEdits_NullEditOptions() {
        Long currentVideoId = testVideo.getId();
        Video.VideoStatus initialStatus = testVideo.getStatus();

        videoProcessingService.processVideoEdits(currentVideoId, null, testUser.getUsername());

        Awaitility.await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Video videoNotProcessed = videoRepository.findById(currentVideoId).orElseThrow();
            assertThat(videoNotProcessed.getStatus()).isEqualTo(initialStatus);
            assertThat(videoNotProcessed.getProcessedStoragePath()).isNull();
        });
    }
}
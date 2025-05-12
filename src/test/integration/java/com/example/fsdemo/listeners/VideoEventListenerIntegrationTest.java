package com.example.fsdemo.listeners;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.SseService;
import com.example.fsdemo.service.VideoStatusUpdater;
import com.example.fsdemo.web.dto.VideoStatusUpdateDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.data.domain.Pageable;


import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@SpringBootTest
class VideoEventListenerIntegrationTest {

    @Autowired
    private SseService sseService;

    @Autowired
    private VideoStatusUpdater videoStatusUpdater;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    private TestSseEmitter testSseEmitter;
    private AppUser testOwner;
    private String currentTestUsername;

    private static final String TEST_USERNAME_PREFIX = "testUserSse";
    private static final long ASYNC_AWAIT_TIMEOUT_SECONDS = 10;
    private static final long SSE_EMITTER_TEST_TIMEOUT = 30000L;
    private static final String PROCESSED_PATH_PREFIX = "processed/some-path-";

    public static class TestSseEmitter extends SseEmitter {
        private final List<VideoStatusUpdateDto> capturedDtos = new CopyOnWriteArrayList<>();

        public TestSseEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(@NonNull Object object, @Nullable MediaType mediaType) {
            if (object instanceof VideoStatusUpdateDto) {
                capturedDtos.add((VideoStatusUpdateDto) object);
            }
        }

        @Override
        public void send(@NonNull SseEventBuilder builder) {
            Set<DataWithMediaType> dataSet = builder.build();
            for (DataWithMediaType dataItem : dataSet) {
                if (dataItem.getData() instanceof VideoStatusUpdateDto dto) {
                    capturedDtos.add(dto);
                    return;
                }
            }
        }

        public List<VideoStatusUpdateDto> getCapturedDtos() {
            return Collections.unmodifiableList(capturedDtos);
        }

        public void clearEvents() {
            capturedDtos.clear();
        }
    }

    @BeforeEach
    void setUp() {
        this.currentTestUsername = TEST_USERNAME_PREFIX + UUID.randomUUID()
                .toString().replaceAll("-", "").substring(0, 9);
        String currentUserEmail = this.currentTestUsername + "@example.com";

        testOwner = appUserRepository.findByUsername(this.currentTestUsername).orElseGet(() -> {
            AppUser newUser = new AppUser(this.currentTestUsername, "password123", "USER", currentUserEmail);
            newUser.setVerified(true);
            return appUserRepository.save(newUser);
        });

        testSseEmitter = new TestSseEmitter(SSE_EMITTER_TEST_TIMEOUT);
        sseService.addEmitter(this.currentTestUsername, testSseEmitter);
        testSseEmitter.clearEvents();
    }

    @AfterEach
    void tearDown() {
        if (testSseEmitter != null) {
            sseService.removeEmitter(this.currentTestUsername, testSseEmitter);
            testSseEmitter.complete();
            testSseEmitter = null;
        }

        List<Video> userVideos = videoRepository.findByOwnerUsername(this.currentTestUsername, Pageable.unpaged()).getContent();
        if (!userVideos.isEmpty()) {
            videoRepository.deleteAll(userVideos);
        }

        if (testOwner != null && testOwner.getId() != null) {
            appUserRepository.findById(testOwner.getId()).ifPresent(appUserRepository::delete);
        }
        testOwner = null;
        currentTestUsername = null;
    }

    private Video createAndSaveTestVideo(Video.VideoStatus initialStatus) {
        return createAndSaveTestVideo(initialStatus, testOwner);
    }

    private Video createAndSaveTestVideo(Video.VideoStatus initialStatus, AppUser owner) {
        Video video = new Video(owner, "Test Description", Instant.now(),
                "test-path-" + UUID.randomUUID() + ".mp4", 1024L, "video/mp4");
        video.setStatus(initialStatus);
        if (initialStatus == Video.VideoStatus.READY || initialStatus == Video.VideoStatus.PROCESSING) {
            video.setProcessedStoragePath(PROCESSED_PATH_PREFIX + UUID.randomUUID() + ".mp4");
        }
        return videoRepository.save(video);
    }

    @Test
    @DisplayName("Event for PROCESSING status should send SSE event")
    void videoStatusToProcessing_SendsSseEvent() {
        Video savedVideo = createAndSaveTestVideo(Video.VideoStatus.UPLOADED);
        String expectedPublicId = savedVideo.getPublicId();

        videoStatusUpdater.updateStatusToProcessing(savedVideo.getId());

        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> !testSseEmitter.getCapturedDtos().isEmpty());

        assertThat(testSseEmitter.getCapturedDtos()).hasSize(1);
        VideoStatusUpdateDto capturedDto = testSseEmitter.getCapturedDtos().getFirst();
        assertThat(capturedDto.publicId()).isEqualTo(expectedPublicId);
        assertThat(capturedDto.status()).isEqualTo(Video.VideoStatus.PROCESSING);
        assertThat(capturedDto.message()).isNull();
    }

    @Test
    @DisplayName("Event for READY status should send SSE event")
    void videoStatusToReady_SendsSseEvent() {
        Video savedVideo = createAndSaveTestVideo(Video.VideoStatus.PROCESSING);
        String expectedPublicId = savedVideo.getPublicId();
        String processedPath = PROCESSED_PATH_PREFIX + UUID.randomUUID() + ".mp4";

        videoStatusUpdater.updateStatusToReady(savedVideo.getId(), processedPath);

        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> !testSseEmitter.getCapturedDtos().isEmpty());

        assertThat(testSseEmitter.getCapturedDtos()).hasSize(1);
        VideoStatusUpdateDto capturedDto = testSseEmitter.getCapturedDtos().getFirst();
        assertThat(capturedDto.publicId()).isEqualTo(expectedPublicId);
        assertThat(capturedDto.status()).isEqualTo(Video.VideoStatus.READY);
        assertThat(capturedDto.message()).isNull();

        Video updatedVideo = videoRepository.findById(savedVideo.getId()).orElseThrow();
        assertThat(updatedVideo.getProcessedStoragePath()).isEqualTo(processedPath);
    }

    @Test
    @DisplayName("Event for FAILED status should send SSE event")
    void videoStatusToFailed_SendsSseEvent() {
        Video savedVideo = createAndSaveTestVideo(Video.VideoStatus.PROCESSING);
        String expectedPublicId = savedVideo.getPublicId();

        videoStatusUpdater.updateStatusToFailed(savedVideo.getId());

        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> !testSseEmitter.getCapturedDtos().isEmpty());

        assertThat(testSseEmitter.getCapturedDtos()).hasSize(1);
        VideoStatusUpdateDto capturedDto = testSseEmitter.getCapturedDtos().getFirst();
        assertThat(capturedDto.publicId()).isEqualTo(expectedPublicId);
        assertThat(capturedDto.status()).isEqualTo(Video.VideoStatus.FAILED);
        assertThat(capturedDto.message()).isEqualTo("Video processing failed.");
    }

    @Test
    @DisplayName("Updating status to PROCESSING when already PROCESSING should still send SSE event")
    void videoStatusToProcessing_WhenAlreadyProcessing_SendsSseEvent() {
        Video savedVideo = createAndSaveTestVideo(Video.VideoStatus.PROCESSING);
        String expectedPublicId = savedVideo.getPublicId();

        videoStatusUpdater.updateStatusToProcessing(savedVideo.getId());

        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> !testSseEmitter.getCapturedDtos().isEmpty());

        assertThat(testSseEmitter.getCapturedDtos()).hasSize(1);
        VideoStatusUpdateDto capturedDto = testSseEmitter.getCapturedDtos().getFirst();
        assertThat(capturedDto.publicId()).isEqualTo(expectedPublicId);
        assertThat(capturedDto.status()).isEqualTo(Video.VideoStatus.PROCESSING);
        assertThat(capturedDto.message()).isNull();
    }

    @Test
    @DisplayName("Complex lifecycle status changes for a single video should all send SSE events")
    void videoStatusChanges_ComplexLifecycle_SendsAllSseEvents() {
        Video video = createAndSaveTestVideo(Video.VideoStatus.UPLOADED);
        String publicId = video.getPublicId();
        String processedPath1 = PROCESSED_PATH_PREFIX + "1-" + UUID.randomUUID() + ".mp4";


        videoStatusUpdater.updateStatusToProcessing(video.getId());
        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                testSseEmitter.getCapturedDtos().stream().anyMatch(dto ->
                        dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.PROCESSING));
        long processingCount1 = testSseEmitter.getCapturedDtos().stream().filter(dto ->
                dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.PROCESSING).count();


        videoStatusUpdater.updateStatusToReady(video.getId(), processedPath1);
        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                testSseEmitter.getCapturedDtos().stream().anyMatch(dto ->
                        dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.READY));


        videoStatusUpdater.updateStatusToProcessing(video.getId());
        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                testSseEmitter.getCapturedDtos().stream().filter(dto ->
                        dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.PROCESSING).count() > processingCount1);


        videoStatusUpdater.updateStatusToFailed(video.getId());
        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).until(() ->
                testSseEmitter.getCapturedDtos().stream().anyMatch(dto ->
                        dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.FAILED));

        List<VideoStatusUpdateDto> dtos = testSseEmitter.getCapturedDtos();
        assertThat(dtos.stream().filter(dto ->
                dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.PROCESSING).count()).isEqualTo(2);
        assertThat(dtos.stream().filter(dto ->
                dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.READY).count()).isEqualTo(1);
        assertThat(dtos.stream().filter(dto ->
                dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.FAILED).count()).isEqualTo(1);

        VideoStatusUpdateDto readyDto = dtos.stream().filter(dto ->
                dto.publicId().equals(publicId) && dto.status() == Video.VideoStatus.READY).findFirst().orElseThrow();
        assertThat(readyDto.message()).isNull();
        Video updatedVideo = videoRepository.findById(video.getId()).orElseThrow();
        assertThat(updatedVideo.getStatus()).isEqualTo(Video.VideoStatus.FAILED);
        assertThat(updatedVideo.getProcessedStoragePath()).isNull();
    }


    @Test
    @DisplayName("Status update when no SseEmitter is registered for the user should not throw error and capture no SSE")
    void videoStatusUpdate_WhenNoSseEmitterForUser_NoSseCaptured() {
        Video savedVideo = createAndSaveTestVideo(Video.VideoStatus.UPLOADED);
        sseService.removeEmitter(this.currentTestUsername, testSseEmitter);
        testSseEmitter.complete();

        TestSseEmitter newTestEmitterForDifferentUser = new TestSseEmitter(SSE_EMITTER_TEST_TIMEOUT);
        String anotherUser = "anotherUser" + UUID.randomUUID().toString().substring(0, 5);
        AppUser otherAppUser = new AppUser(anotherUser, "pwd", "USER", anotherUser + "@example.com");
        otherAppUser.setVerified(true);
        appUserRepository.save(otherAppUser);
        sseService.addEmitter(anotherUser, newTestEmitterForDifferentUser);


        videoStatusUpdater.updateStatusToProcessing(savedVideo.getId());


        await().during(2, TimeUnit.SECONDS).atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> testSseEmitter.getCapturedDtos().isEmpty());

        assertThat(testSseEmitter.getCapturedDtos()).isEmpty();
        assertThat(newTestEmitterForDifferentUser.getCapturedDtos()).isEmpty();


        Video videoAfterUpdate = videoRepository.findById(savedVideo.getId()).orElseThrow();
        assertThat(videoAfterUpdate.getStatus()).isEqualTo(Video.VideoStatus.PROCESSING);

        sseService.removeEmitter(anotherUser, newTestEmitterForDifferentUser);
    }


    @Test
    @DisplayName("Transaction rollback during status update should trigger rollback listener and not send primary SSE event")
    void videoStatusUpdate_RollbackOnUniqueConstraint_NoSseSentForReadyAndStatusUnchanged() {
        Video video1 = createAndSaveTestVideo(Video.VideoStatus.PROCESSING);
        Video video2 = createAndSaveTestVideo(Video.VideoStatus.UPLOADED);

        String conflictingProcessedPath = PROCESSED_PATH_PREFIX + "shared-" + UUID.randomUUID() + ".mp4";


        videoStatusUpdater.updateStatusToReady(video2.getId(), conflictingProcessedPath);
        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> testSseEmitter.getCapturedDtos().stream()
                        .anyMatch(dto ->
                                dto.publicId().equals(video2.getPublicId()) && dto.status() == Video.VideoStatus.READY));
        testSseEmitter.clearEvents();


        Long video1Id = video1.getId();

        assertThatThrownBy(() -> videoStatusUpdater.updateStatusToReady(video1Id, conflictingProcessedPath))
                .isInstanceOf(DataIntegrityViolationException.class);


        await().during(2, TimeUnit.SECONDS).atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> testSseEmitter.getCapturedDtos().isEmpty());

        assertThat(testSseEmitter.getCapturedDtos()).isEmpty();


        Video video1AfterAttempt = videoRepository.findById(video1.getId()).orElseThrow();
        assertThat(video1AfterAttempt.getStatus()).isEqualTo(Video.VideoStatus.PROCESSING);
        assertThat(video1AfterAttempt.getProcessedStoragePath()).isNotEqualTo(conflictingProcessedPath);
    }


    @Test
    @DisplayName("Multiple status changes for different videos should all send SSE events")
    void multipleStatusChanges_DifferentVideos_AllProcessed() {
        Video video1 = createAndSaveTestVideo(Video.VideoStatus.UPLOADED);
        Video video2 = createAndSaveTestVideo(Video.VideoStatus.UPLOADED);

        String publicId1 = video1.getPublicId();
        String publicId2 = video2.getPublicId();
        String processedPath1 = PROCESSED_PATH_PREFIX + publicId1 + ".mp4";

        videoStatusUpdater.updateStatusToProcessing(video1.getId());
        videoStatusUpdater.updateStatusToProcessing(video2.getId());

        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> testSseEmitter.getCapturedDtos().stream()
                        .filter(dto -> dto.status() == Video.VideoStatus.PROCESSING).count() >= 2);

        videoStatusUpdater.updateStatusToReady(video1.getId(), processedPath1);

        await().atMost(ASYNC_AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .until(() -> testSseEmitter.getCapturedDtos().stream()
                        .anyMatch(dto -> dto.publicId().equals(publicId1) && dto.status() == Video.VideoStatus.READY));

        List<VideoStatusUpdateDto> dtos = testSseEmitter.getCapturedDtos();

        List<VideoStatusUpdateDto> video1ProcessingEvents = dtos.stream()
                .filter(dto -> dto.publicId().equals(publicId1) && dto.status() == Video.VideoStatus.PROCESSING)
                .toList();
        List<VideoStatusUpdateDto> video2ProcessingEvents = dtos.stream()
                .filter(dto -> dto.publicId().equals(publicId2) && dto.status() == Video.VideoStatus.PROCESSING)
                .toList();
        List<VideoStatusUpdateDto> video1ReadyEvents = dtos.stream()
                .filter(dto -> dto.publicId().equals(publicId1) && dto.status() == Video.VideoStatus.READY)
                .toList();

        assertThat(video1ProcessingEvents).hasSize(1);
        assertThat(video1ProcessingEvents.getFirst().message()).isNull();

        assertThat(video2ProcessingEvents).hasSize(1);
        assertThat(video2ProcessingEvents.getFirst().message()).isNull();

        assertThat(video1ReadyEvents).hasSize(1);
        assertThat(video1ReadyEvents.getFirst().message()).isNull();

        Video updatedVideo1 = videoRepository.findById(video1.getId()).orElseThrow();
        assertThat(updatedVideo1.getStatus()).isEqualTo(Video.VideoStatus.READY);
        assertThat(updatedVideo1.getProcessedStoragePath()).isEqualTo(processedPath1);

        Video updatedVideo2 = videoRepository.findById(video2.getId()).orElseThrow();
        assertThat(updatedVideo2.getStatus()).isEqualTo(Video.VideoStatus.PROCESSING);
    }
}
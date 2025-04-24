package com.example.fsdemo.web;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.exceptions.VideoValidationException;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.security.JwtService;
import com.example.fsdemo.service.*;
import com.example.fsdemo.web.dto.EditOptions;
import com.example.fsdemo.web.dto.UpdateVideoRequest;
import com.example.fsdemo.web.dto.VideoResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VideoController Integration Tests")
class VideoControllerTest {

    private static final String TEST_USERNAME = "testuploader";
    private static final String TEST_PASSWORD = "testpass12345";
    private static final String TEST_EMAIL = "uploader@example.com";
    private static final String SAMPLE_FILENAME_ORIGINAL = "sample.mp4";
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String DEFAULT_STORAGE_PATH = "default-storage-path-returned-by-mock.mp4";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private VideoRepository videoRepository;
    @MockitoBean
    private AppUserRepository appUserRepository;
    @MockitoBean
    private VideoStorageService storageService;
    @MockitoBean
    private VideoProcessingService videoProcessingService;
    @MockitoBean
    private VideoSecurityService videoSecurityService;
    @MockitoBean
    private VideoUploadValidator videoUploadValidator;
    @MockitoBean
    private VideoStatusUpdater videoStatusUpdater;

    private AppUser testUser;
    private String jwtTokenHeader;
    private Cookie fingerprintCookie;
    private byte[] sampleVideoContent;

    @BeforeEach
    void setUp() throws Exception {
        // Reset mocks before each test
        Mockito.reset(storageService, videoSecurityService, videoRepository,
                videoProcessingService, appUserRepository, videoUploadValidator);

        // Create test user
        testUser = createTestUser();

        // Mock user repo
        given(appUserRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(testUser));

        // Load sample video content
        sampleVideoContent = loadSampleVideo();

        // Configure default mock behaviors (can be overridden)
        configureDefaultMockBehavior();

        // Authenticate and get token/cookie
        authenticateAndGetTokenAndCookie();
    }

    // ============ HELPER METHODS ============

    private AppUser createTestUser() {
        AppUser user = new AppUser(
                TEST_USERNAME,
                passwordEncoder.encode(TEST_PASSWORD),
                "USER",
                TEST_EMAIL
        );
        user.setId(1L);
        user.setVerified(true);
        return user;
    }

    private byte[] loadSampleVideo() throws Exception {
        return Files.readAllBytes(Paths.get("src/test/resources/" + SAMPLE_FILENAME_ORIGINAL));
    }

    private void configureDefaultMockBehavior() {
        // Default: Validation passes
        doNothing().when(videoUploadValidator).validate(any(MultipartFile.class));
        // Default: Storage succeeds
        given(storageService.store(any(MultipartFile.class), anyLong(), anyString()))
                .willReturn(DEFAULT_STORAGE_PATH);
        // Default: Loading succeeds (if path matches default store)
        given(storageService.load(DEFAULT_STORAGE_PATH))
                .willReturn(new ByteArrayResource(sampleVideoContent));
        // Default: Deletion succeeds
        doNothing().when(storageService).delete(anyString());
    }

    private void authenticateAndGetTokenAndCookie() throws Exception {

        given(appUserRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(testUser));

        MvcResult result = mockMvc.perform(post("/api/auth/login") // Corrected path from SecurityConfig
                        .content(String.format("{\"username\":\"%s\", \"password\":\"%s\"}", TEST_USERNAME, TEST_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.AUTHORIZATION))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        jwtTokenHeader = result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
        assertThat(jwtTokenHeader).startsWith("Bearer ");

        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).contains(JwtService.FINGERPRINT_COOKIE_NAME + "=");

        assert setCookieHeader != null;
        String cookieValue = setCookieHeader.split(";")[0].split("=")[1];
        fingerprintCookie = new Cookie(JwtService.FINGERPRINT_COOKIE_NAME, cookieValue);
        fingerprintCookie.setHttpOnly(true);
        fingerprintCookie.setSecure(true);
        fingerprintCookie.setPath("/api");

        assertThat(fingerprintCookie.getValue()).isNotBlank();
    }

    private MockHttpServletRequestBuilder addAuth(MockHttpServletRequestBuilder builder) {
        if (jwtTokenHeader != null && fingerprintCookie != null) {
            return builder
                    .header(HttpHeaders.AUTHORIZATION, jwtTokenHeader)
                    .cookie(fingerprintCookie);
        } else {
            throw new IllegalStateException("Auth details not available for test request.");
        }
    }

    private MockMultipartFile createTestVideoFile(String originalFilename) {
        return new MockMultipartFile(
                "file", originalFilename, VIDEO_MIME_TYPE, sampleVideoContent);
    }

    private MockMultipartFile createTestVideoFile() {
        return createTestVideoFile(SAMPLE_FILENAME_ORIGINAL);
    }

    // ============ UPLOAD TESTS ============
    @Nested
    @DisplayName("POST /api/videos (Upload)")
    class UploadVideoTests {

        @Test
        @DisplayName("✅ Should succeed with valid file, auth, and description")
        void uploadVideo_Success() throws Exception {
            // Arrange
            MockMultipartFile videoFile = createTestVideoFile();
            String description = "Test description";
            String expectedStorageFilenameRegex = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.mp4";
            String specificStoragePath = "uuid-like-path.mp4"; // Mock return

            // Capture filename passed to storage
            ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
            given(storageService.store(any(MultipartFile.class), eq(testUser.getId()), filenameCaptor.capture()))
                    .willReturn(specificStoragePath);

            // Capture video saved to repo
            ArgumentCaptor<Video> videoSaveCaptor = ArgumentCaptor.forClass(Video.class);
            given(videoRepository.save(videoSaveCaptor.capture())).willAnswer(invocation -> {
                Video videoToSave = invocation.getArgument(0);
                videoToSave.setId(99L); // Simulate ID generation
                return videoToSave;
            });

            // Act & Assert
            mockMvc.perform(addAuth(multipart("/api/videos")
                            .file(videoFile)
                            .param("description", description)
                            .accept(MediaType.APPLICATION_JSON)))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.id").value(99L))
                    .andExpect(jsonPath("$.description").value(description))
                    .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                    .andExpect(jsonPath("$.fileSize").value(sampleVideoContent.length));

            // Verify interactions in order
            InOrder orderVerifier = inOrder(videoUploadValidator, storageService, videoRepository);
            orderVerifier.verify(videoUploadValidator).validate(any(MultipartFile.class));
            orderVerifier.verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
            orderVerifier.verify(videoRepository).save(any(Video.class));

            // Verify captured values
            Video savedVideo = videoSaveCaptor.getValue();
            assertThat(savedVideo.getDescription()).isEqualTo(description);
            assertThat(savedVideo.getOwner().getUsername()).isEqualTo(TEST_USERNAME);
            assertThat(savedVideo.getGeneratedFilename()).matches(expectedStorageFilenameRegex);
            assertThat(savedVideo.getStoragePath()).isEqualTo(specificStoragePath);
            assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.UPLOADED);
            assertThat(filenameCaptor.getValue()).isEqualTo(savedVideo.getGeneratedFilename()); // Ensure generated name used
        }

        @Test
        @DisplayName("✅ Should succeed with empty description")
        void uploadVideo_SuccessEmptyDescription() throws Exception {
            MockMultipartFile videoFile = createTestVideoFile();
            ArgumentCaptor<Video> videoSaveCaptor = ArgumentCaptor.forClass(Video.class);
            given(videoRepository.save(videoSaveCaptor.capture())).willAnswer(invocation -> {
                Video videoToSave = invocation.getArgument(0);
                videoToSave.setId(1L);
                return videoToSave;
            });

            mockMvc.perform(addAuth(multipart("/api/videos")
                            .file(videoFile)
                            .param("description", "")))
                    .andExpect(status().isCreated());

            verify(videoUploadValidator).validate(any(MultipartFile.class));
            verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
            assertThat(videoSaveCaptor.getValue().getDescription()).isEmpty();
        }

        @Test
        @DisplayName("❌ Should return 401 Unauthorized without token/cookie")
        void uploadVideo_FailUnauthorized() throws Exception {
            mockMvc.perform(multipart("/api/videos").file(createTestVideoFile()))
                    .andExpect(status().isUnauthorized());

            verifyNoInteractions(videoUploadValidator, storageService, videoRepository);
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request if validation fails (e.g., size)")
        void uploadVideo_FailValidationSize() throws Exception {
            // Arrange: Configure validator mock to throw exception
            VideoValidationException validationEx = new VideoValidationException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "File too big");
            doThrow(validationEx).when(videoUploadValidator).validate(any(MultipartFile.class));

            // Act & Assert
            mockMvc.perform(addAuth(multipart("/api/videos").file(createTestVideoFile())))
                    .andExpect(status().isPayloadTooLarge()); // Expect the status from the exception

            // Verify only validator was called
            verify(videoUploadValidator).validate(any(MultipartFile.class));
            verifyNoInteractions(storageService, videoRepository);
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request if validation fails (e.g., type)")
        void uploadVideo_FailValidationType() throws Exception {
            VideoValidationException validationEx = new VideoValidationException(
                    HttpStatus.BAD_REQUEST, "Invalid type");
            doThrow(validationEx).when(videoUploadValidator).validate(any(MultipartFile.class));

            mockMvc.perform(addAuth(multipart("/api/videos").file(createTestVideoFile())))
                    .andExpect(status().isBadRequest());

            verify(videoUploadValidator).validate(any(MultipartFile.class));
            verifyNoInteractions(storageService, videoRepository);
        }


        @Test
        @DisplayName("❌ Should return 500 Internal Server Error if storage fails")
        void uploadVideo_FailStorage() throws Exception {
            // Arrange: Configure storage mock to throw exception AFTER validation passes
            VideoStorageException storageEx = new VideoStorageException("Disk full");
            given(storageService.store(any(MultipartFile.class), eq(testUser.getId()), anyString()))
                    .willThrow(storageEx);

            // Act & Assert
            mockMvc.perform(addAuth(multipart("/api/videos").file(createTestVideoFile())))
                    .andExpect(status().isInternalServerError()); // Handled by GlobalExceptionHandler

            // Verify validator AND store were called
            verify(videoUploadValidator).validate(any(MultipartFile.class));
            verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
            // Verify repo save was NOT called due to the exception
            verify(videoRepository, never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request if 'file' part is missing")
        void uploadVideo_FailMissingFilePart() throws Exception {
            mockMvc.perform(addAuth(multipart("/api/videos").param("description", "No file")))
                    .andExpect(status().isBadRequest()); // Spring handles missing required part

            verifyNoInteractions(videoUploadValidator, storageService, videoRepository);
        }
    }

    // ============ LIST VIDEOS TESTS ============
    @Nested
    @DisplayName("GET /api/videos (List)")
    class ListVideoTests {
        @Test
        @DisplayName("✅ Should return only the authenticated user's videos")
        void listVideos_Success() throws Exception {
            Video userVideo1 = new Video(testUser, "uuid-user1.mp4", "User Video 1",
                    Instant.now(), "p1", 100L, VIDEO_MIME_TYPE);
            userVideo1.setId(1L);
            Video userVideo2 = new Video(testUser, "uuid-user2.mp4", "User Video 2",
                    Instant.now(), "p2", 200L, VIDEO_MIME_TYPE);
            userVideo2.setId(2L);

            given(videoRepository.findByOwnerUsername(TEST_USERNAME))
                    .willReturn(Arrays.asList(userVideo1, userVideo2));

            MvcResult result = mockMvc.perform(addAuth(get("/api/videos").accept(MediaType.APPLICATION_JSON)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].id").value(1L))
                    .andExpect(jsonPath("$[0].ownerUsername").value(TEST_USERNAME))
                    .andExpect(jsonPath("$[1].id").value(2L))
                    .andExpect(jsonPath("$[1].ownerUsername").value(TEST_USERNAME))
                    .andReturn();

            List<VideoResponse> videoResponses = objectMapper.readValue(
                    result.getResponse().getContentAsString(), new TypeReference<>() {
                    });
            assertThat(videoResponses).extracting(VideoResponse::ownerUsername).containsOnly(TEST_USERNAME);

            verify(videoRepository).findByOwnerUsername(TEST_USERNAME);
        }

        @Test
        @DisplayName("✅ Should return empty list when user has no videos")
        void listVideos_SuccessEmpty() throws Exception {
            given(videoRepository.findByOwnerUsername(TEST_USERNAME)).willReturn(Collections.emptyList());

            mockMvc.perform(addAuth(get("/api/videos").accept(MediaType.APPLICATION_JSON)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));

            verify(videoRepository).findByOwnerUsername(TEST_USERNAME);
        }

        @Test
        @DisplayName("❌ Should return 401 Unauthorized without token/cookie")
        void listVideos_FailUnauthorized() throws Exception {
            mockMvc.perform(get("/api/videos").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isUnauthorized());
            verify(videoRepository, never()).findByOwnerUsername(anyString());
        }
    }

    // ============ GET DETAILS TESTS ============
    @Nested
    @DisplayName("GET /api/videos/{id} (Details)")
    class GetVideoDetailsTests {
        @Test
        @DisplayName("✅ Should return video details when user is owner")
        void getVideoDetails_SuccessOwner() throws Exception {
            Long videoId = 1L;
            Video video = new Video(testUser, "uuid-details.mp4", "Details",
                    Instant.now(), "path/details", 123L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);

            mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(videoId))
                    .andExpect(jsonPath("$.description").value("Details"))
                    .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                    .andExpect(jsonPath("$.fileSize").value(123L));

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
        }

        @Test
        @DisplayName("❌ Should return 404 Not Found when video doesn't exist")
        void getVideoDetails_FailNotFound() throws Exception {
            Long videoId = 99L;
            given(videoRepository.findById(videoId)).willReturn(Optional.empty());

            mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                    .andExpect(status().isNotFound());

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService, never()).isOwner(anyLong(), anyString());
        }

        @Test
        @DisplayName("❌ Should return 403 Forbidden when user cannot view (is not owner)")
        void getVideoDetails_FailForbidden() throws Exception {
            Long videoId = 2L;
            AppUser anotherOwner = new AppUser("another", "pass", "U", "a@a.com");
            anotherOwner.setId(99L);
            Video video = new Video(anotherOwner, "private.mp4", "Private",
                    Instant.now(), "path/private", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(false); // User is NOT owner

            mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                    .andExpect(status().isForbidden());

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
        }

        @Test
        @DisplayName("❌ Should return 401 Unauthorized without token/cookie")
        void getVideoDetails_FailUnauthorized() throws Exception {
            mockMvc.perform(get("/api/videos/{id}", 1L))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(videoRepository, videoSecurityService);
        }
    }


    // ============ DOWNLOAD TESTS ============
    @Nested
    @DisplayName("GET /api/videos/{id}/download (Download)")
    class DownloadVideoTests {

        @Test
        @DisplayName("✅ Should download ORIGINAL when status is UPLOADED/PROCESSING/FAILED")
        void downloadVideo_Original() throws Exception {
            Long videoId = 1L;
            String originalFilename = "original-uuid.mp4";
            String originalPath = "storage/" + originalFilename; // Path stored in DB
            Video video = new Video(testUser, originalFilename, "Orig", Instant.now(), originalPath, 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.UPLOADED); // Test UPLOADED status

            Resource videoResource = new ByteArrayResource("original content".getBytes());

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            // FIX: Mock isOwner instead of canView
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            given(storageService.load(originalPath)).willReturn(videoResource); // Load using the DB path

            mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(VIDEO_MIME_TYPE))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, matchesPattern
                            ("attachment; filename=\"[a-f0-9-]+\\.mp4\"")))
                    .andExpect(content().bytes("original content".getBytes()));

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verify(storageService).load(originalPath);
            verify(storageService, never()).load(video.getProcessedStoragePath());
        }

        @Test
        @DisplayName("✅ Should download PROCESSED when status is READY")
        void downloadVideo_Processed() throws Exception {
            Long videoId = 2L;
            String originalFilename = "orig-uuid.mp4";
            String originalPath = "storage/" + originalFilename;
            String processedFilename = "processed-uuid.mp4";

            Video video = new Video(testUser, originalFilename, "Proc",
                    Instant.now(), originalPath, 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.READY);
            video.setProcessedStoragePath(processedFilename);

            Resource videoResource = new ByteArrayResource("processed content".getBytes());

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            given(storageService.load(processedFilename)).willReturn(videoResource);

            mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(VIDEO_MIME_TYPE))
                    .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, matchesPattern
                            ("attachment; filename=\"[a-f0-9-]+\\.mp4\"")))
                    .andExpect(content().bytes("processed content".getBytes()));

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verify(storageService).load(processedFilename); // Verify correct path loaded
            verify(storageService, never()).load(originalPath); // Ensure original wasn't loaded
        }

        @Test
        @DisplayName("❌ Should return 404 Not Found when video record exists but file is missing")
        void downloadVideo_FailStorageFileNotFound() throws Exception {
            Long videoId = 3L;
            String storagePath = "path/missing.mp4";
            Video video = new Video(testUser, "missing-uuid.mp4", "Missing",
                    Instant.now(), storagePath, 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.UPLOADED);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            // Simulate storage service throwing exception indicating file not found
            given(storageService.load(storagePath)).willThrow(new VideoStorageException("Could not read file: " + storagePath));

            mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                    .andExpect(status().isNotFound()); // Expect 404 as the file resource is gone

            verify(videoRepository).findById(videoId);
            // FIX: Verify isOwner instead of canView
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verify(storageService).load(storagePath);
        }

        @Test
        @DisplayName("❌ Should return 500 Internal Server Error for other storage load errors")
        void downloadVideo_FailStorageOtherError() throws Exception {
            Long videoId = 4L;
            String storagePath = "path/error.mp4";
            Video video = new Video(testUser, "error-uuid.mp4", "Error",
                    Instant.now(), storagePath, 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.UPLOADED);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            given(storageService.load(storagePath)).willThrow(new VideoStorageException("General storage failure"));

            mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                    .andExpect(status().isInternalServerError());

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verify(storageService).load(storagePath);
        }


        @Test
        @DisplayName("❌ Should return 404 Not Found when video record doesn't exist")
        void downloadVideo_FailVideoNotFound() throws Exception {
            Long videoId = 99L;
            given(videoRepository.findById(videoId)).willReturn(Optional.empty());

            mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                    .andExpect(status().isNotFound());

            verify(videoRepository).findById(videoId);
            verifyNoInteractions(videoSecurityService, storageService);
        }

        @Test
        @DisplayName("❌ Should return 403 Forbidden when user cannot view")
        void downloadVideo_FailForbidden() throws Exception {
            Long videoId = 5L;
            AppUser anotherOwner = new AppUser("another", "pass", "U", "a@a.com");
            anotherOwner.setId(99L);
            Video video = new Video(anotherOwner, "private-dl.mp4", "Private",
                    Instant.now(), "path/private-dl", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(false); // Cannot view (not owner)

            mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                    .andExpect(status().isForbidden());

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("❌ Should return 401 Unauthorized without token/cookie")
        void downloadVideo_FailUnauthorized() throws Exception {
            mockMvc.perform(get("/api/videos/{id}/download", 1L))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(videoRepository, videoSecurityService, storageService);
        }
    }

    // ============ PROCESS TESTS ============
    @Nested
    @DisplayName("POST /api/videos/{id}/process (Process)")
    class ProcessVideoTests {

        @Autowired
        private VideoRepository videoRepository;
        @Autowired
        private VideoSecurityService videoSecurityService;

        @Test
        @DisplayName("✅ Should return 202 Accepted and trigger processing when status is UPLOADED")
        void processVideo_SuccessUploadedStatus() throws Exception {
            Long videoId = 1L;
            Video video = new Video(testUser, "proc-up.mp4", "Desc",
                    Instant.now(), "p1", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.UPLOADED);

            EditOptions validOptions = new EditOptions(10.0, 20.0, false, 720);
            String requestBody = objectMapper.writeValueAsString(validOptions);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);
            // Mock the call to the updater mock
            doNothing().when(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId);
            // Mock the call to the processing service mock
            doNothing()
                    .when(VideoControllerTest.this.videoProcessingService)
                    .processVideoEdits(anyLong(), any(EditOptions.class), anyString());

            mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isAccepted());

            // Verify sequence and interactions
            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
            verify(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId);
            verify(VideoControllerTest.this.videoProcessingService).processVideoEdits(videoId, validOptions, testUser.getUsername());
        }

        @Test
        @DisplayName("✅ Should return 202 Accepted and trigger processing when status is READY")
        void processVideo_SuccessReadyStatus() throws Exception {
            Long videoId = 2L;
            Video video = new Video(testUser, "proc-ready.mp4", "Desc",
                    Instant.now(), "p2", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.READY);
            video.setProcessedStoragePath("old-processed.mp4");

            EditOptions validOptions = new EditOptions(null, null, true, 480);
            String requestBody = objectMapper.writeValueAsString(validOptions);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);
            doNothing().when(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId);
            doNothing().when(VideoControllerTest.this.videoProcessingService).processVideoEdits(anyLong(), any(EditOptions.class), anyString());

            mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isAccepted());

            // Verify sequence and interactions
            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
            verify(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId);
            verify(VideoControllerTest.this.videoProcessingService).processVideoEdits(videoId, validOptions, testUser.getUsername());
        }

        @Test
        @DisplayName("❌ Should return 409 Conflict when already PROCESSING")
        void processVideo_FailConflictProcessing() throws Exception {
            Long videoId = 3L;
            Video video = new Video(testUser, "proc-conflict.mp4", "Desc",
                    Instant.now(), "p3", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.PROCESSING);

            EditOptions validOptions = new EditOptions(null, null, false, 720);
            String requestBody = objectMapper.writeValueAsString(validOptions);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);
            // Configure updater mock to throw the expected exception
            doThrow(new IllegalStateException("Video cannot be processed in its current state: PROCESSING"))
                    .when(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId);

            mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isConflict());

            // Verify sequence
            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
            verify(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId); // Updater mock is called, but throws
            verifyNoInteractions(VideoControllerTest.this.videoProcessingService);
        }

        @Test
        @DisplayName("❌ Should return 409 Conflict when status is FAILED")
        void processVideo_FailConflictFailed() throws Exception {
            Long videoId = 4L;
            Video video = new Video(testUser, "proc-failed.mp4", "Desc", Instant.now(), "p4", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.FAILED);

            EditOptions validOptions = new EditOptions(null, null, false, 720);
            String requestBody = objectMapper.writeValueAsString(validOptions);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);
            // Configure updater mock to throw the expected exception
            doThrow(new IllegalStateException("Video cannot be processed in its current state: FAILED"))
                    .when(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId);

            mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isConflict());

            // Verify sequence
            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
            verify(VideoControllerTest.this.videoStatusUpdater).updateStatusToProcessing(videoId); // Updater mock is called, but throws
            verifyNoInteractions(VideoControllerTest.this.videoProcessingService);
        }

        @Test
        @DisplayName("❌ Should return 403 Forbidden when user is not owner")
        void processVideo_FailForbidden() throws Exception {
            Long videoId = 5L;
            // Video exists
            AppUser realOwner = new AppUser("realOwner", "pass", "U", "r@r.com");
            realOwner.setId(99L);
            Video video = new Video(realOwner, "forbidden-proc.mp4", "Desc",
                    Instant.now(), "p5", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            EditOptions validOptions = new EditOptions(null, null, false, 720);
            String requestBody = objectMapper.writeValueAsString(validOptions);

            // Mock findById to return the video
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            // Mock isOwner check to return false
            given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(false); // Not owner

            mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden());

            // Verify the sequence: findById was called, then isOwner was called.
            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, testUser.getUsername());

            // Verify status updater and processing service were NOT called
            verifyNoInteractions(VideoControllerTest.this.videoStatusUpdater);
            verifyNoInteractions(VideoControllerTest.this.videoProcessingService);
        }


        @Test
        @DisplayName("❌ Should return 400 Bad Request for invalid EditOptions")
        void processVideo_FailInvalidOptions() throws Exception {
            Long videoId = 6L;
            Video video = new Video(testUser, "inv-opt.mp4", "Desc",
                    Instant.now(), "p6", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setStatus(VideoStatus.UPLOADED);

            // Invalid: resolution too low (example based on EditOptions constraints)
            EditOptions invalidOptions = new EditOptions(null, null, false, 100);
            String requestBody = objectMapper.writeValueAsString(invalidOptions);

            // Mock findById needed for the initial check in the controller
            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);

            mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest()); // Expect validation failure handled by Spring/ExceptionHandler

            // Verify that the updater and processor were not called due to validation failure
            verifyNoInteractions(VideoControllerTest.this.videoStatusUpdater);
            verifyNoInteractions(VideoControllerTest.this.videoProcessingService);
        }

        @Test
        @DisplayName("❌ Should return 404 Not Found when video doesn't exist")
        void processVideo_FailNotFound() throws Exception {
            Long videoId = 99L;
            EditOptions validOptions = new EditOptions(null, null, false, 720);
            String requestBody = objectMapper.writeValueAsString(validOptions);

            given(videoRepository.findById(videoId)).willReturn(Optional.empty()); // Mock findById to return empty

            mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound());

            verify(videoRepository).findById(videoId); // Verify findById WAS called (by controller)
            verifyNoInteractions(videoSecurityService);
            verifyNoInteractions(VideoControllerTest.this.videoStatusUpdater);
            verifyNoInteractions(VideoControllerTest.this.videoProcessingService);
        }

        @Test
        @DisplayName("❌ Should return 401 Unauthorized without token/cookie")
        void processVideo_FailUnauthorized() throws Exception {
            EditOptions validOptions = new EditOptions(null, null, false, 720);
            String requestBody = objectMapper.writeValueAsString(validOptions);
            mockMvc.perform(post("/api/videos/{id}/process", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(videoRepository, videoSecurityService, videoStatusUpdater, videoProcessingService);
        }
    }

    // ============ UPDATE DESCRIPTION TESTS ============
    @Nested
    @DisplayName("PUT /api/videos/{id} (Update Description)")
    class UpdateDescriptionTests {
        @Test
        @DisplayName("✅ Should update description and return OK when owner")
        void updateDescription_Success() throws Exception {
            Long videoId = 1L;
            String oldDesc = "Old";
            String newDesc = "New Valid Description";
            Video video = new Video(testUser, "update-desc.mp4", oldDesc, Instant.now(), "p1", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            UpdateVideoRequest request = new UpdateVideoRequest(newDesc);
            String requestBody = objectMapper.writeValueAsString(request);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            given(videoRepository.save(any(Video.class))).willAnswer(inv -> inv.getArgument(0));

            mockMvc.perform(addAuth(put("/api/videos/{id}", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(videoId))
                    .andExpect(jsonPath("$.description").value(newDesc));

            ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
            verify(videoRepository).save(videoCaptor.capture());
            assertThat(videoCaptor.getValue().getDescription()).isEqualTo(newDesc);
            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
        }

        @Test
        @DisplayName("❌ Should return 400 Bad Request for invalid description format")
        void updateDescription_FailInvalidFormat() throws Exception {
            Long videoId = 2L;
            String invalidDesc = "Invalid <script>";
            Video video = new Video(testUser, "inv-desc.mp4", "Old",
                    Instant.now(), "p2", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            UpdateVideoRequest request = new UpdateVideoRequest(invalidDesc);
            String requestBody = objectMapper.writeValueAsString(request);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);

            mockMvc.perform(addAuth(put("/api/videos/{id}", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isBadRequest());

            verify(videoRepository, never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Should return 403 Forbidden when not owner")
        void updateDescription_FailForbidden() throws Exception {
            Long videoId = 3L;
            AppUser realOwner = new AppUser("realOwner", "pass", "U", "r@r.com");
            realOwner.setId(99L);
            Video video = new Video(realOwner, "forbidden-upd.mp4", "Old",
                    Instant.now(), "p3", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            UpdateVideoRequest request = new UpdateVideoRequest("New Desc");
            String requestBody = objectMapper.writeValueAsString(request);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(false); // Not owner

            mockMvc.perform(addAuth(put("/api/videos/{id}", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isForbidden());

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verify(videoRepository, never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Should return 404 Not Found when video doesn't exist")
        void updateDescription_FailNotFound() throws Exception {
            Long videoId = 99L;
            UpdateVideoRequest request = new UpdateVideoRequest("New Desc");
            String requestBody = objectMapper.writeValueAsString(request);

            given(videoRepository.findById(videoId)).willReturn(Optional.empty());

            mockMvc.perform(addAuth(put("/api/videos/{id}", videoId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isNotFound());

            verify(videoRepository).findById(videoId);
            verifyNoInteractions(videoSecurityService);
            verify(videoRepository, never()).save(any(Video.class));
        }

        @Test
        @DisplayName("❌ Should return 401 Unauthorized without token/cookie")
        void updateDescription_FailUnauthorized() throws Exception {
            UpdateVideoRequest request = new UpdateVideoRequest("New Desc");
            String requestBody = objectMapper.writeValueAsString(request);
            mockMvc.perform(put("/api/videos/{id}", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(videoRepository, videoSecurityService);
        }
    }


    // ============ DELETE TESTS ============
    @Nested
    @DisplayName("DELETE /api/videos/{id} (Delete)")
    class DeleteVideoTests {

        @Test
        @DisplayName("✅ Should delete DB record and call storage delete for original and processed files")
        void deleteVideo_Success() throws Exception {
            Long videoId = 1L;
            String originalPath = "path/orig-del.mp4";
            String processedPath = "path/proc-del.mp4";
            Video video = new Video(testUser, "del-uuid.mp4", "To Del",
                    Instant.now(), originalPath, 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setProcessedStoragePath(processedPath); // Has processed file

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            doNothing().when(videoRepository).delete(video); // Mock DB delete
            doNothing().when(storageService).delete(originalPath);
            doNothing().when(storageService).delete(processedPath);

            mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                    .andExpect(status().isNoContent());

            // Verify order: find, check perm, delete DB, delete files
            InOrder order = inOrder(videoRepository, videoSecurityService, storageService);
            order.verify(videoRepository).findById(videoId);
            order.verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            order.verify(videoRepository).delete(video);
            order.verify(storageService).delete(originalPath);
            order.verify(storageService).delete(processedPath);
        }

        @Test
        @DisplayName("✅ Should succeed even if processed file path is null/blank")
        void deleteVideo_SuccessNoProcessedFile() throws Exception {
            Long videoId = 2L;
            String originalPath = "path/orig-only-del.mp4";
            Video video = new Video(testUser, "del-orig-uuid.mp4", "To Del",
                    Instant.now(), originalPath, 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setProcessedStoragePath(null); // No processed file

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            doNothing().when(videoRepository).delete(video);
            doNothing().when(storageService).delete(originalPath);

            mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                    .andExpect(status().isNoContent());

            InOrder order = inOrder(videoRepository, videoSecurityService, storageService);
            order.verify(videoRepository).findById(videoId);
            order.verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            order.verify(videoRepository).delete(video);
            order.verify(storageService).delete(originalPath);
            verify(storageService, never()).delete(isNull());
            verify(storageService, never()).delete("");
        }


        @Test
        @DisplayName("✅ Should return 204 No Content even if storage delete fails")
        void deleteVideo_SuccessDespiteStorageDeleteFailure() throws Exception {
            Long videoId = 3L;
            String originalPath = "path/fail-del-orig.mp4";
            String processedPath = "path/fail-del-proc.mp4";
            Video video = new Video(testUser, "fail-del-uuid.mp4", "Fail Del",
                    Instant.now(), originalPath, 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);
            video.setProcessedStoragePath(processedPath);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);
            doNothing().when(videoRepository).delete(video); // DB delete succeeds
            // Storage delete fails
            doThrow(new VideoStorageException("Cannot delete file")).when(storageService).delete(originalPath);
            doThrow(new VideoStorageException("Cannot delete file")).when(storageService).delete(processedPath);

            mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                    .andExpect(status().isNoContent());

            // Verify DB delete happened, and storage delete was attempted
            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verify(videoRepository).delete(video);
            verify(storageService).delete(originalPath);
            verify(storageService).delete(processedPath);
        }

        @Test
        @DisplayName("❌ Should return 403 Forbidden when not owner")
        void deleteVideo_FailForbidden() throws Exception {
            Long videoId = 4L;
            AppUser realOwner = new AppUser("realOwner", "pass", "U", "r@r.com");
            realOwner.setId(99L);
            Video video = new Video(realOwner, "forbidden-del.mp4", "Old",
                    Instant.now(), "p4", 100L, VIDEO_MIME_TYPE);
            video.setId(videoId);

            given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
            given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(false); // Cannot delete (not owner)

            mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                    .andExpect(status().isForbidden());

            verify(videoRepository).findById(videoId);
            verify(videoSecurityService).isOwner(videoId, TEST_USERNAME);
            verify(videoRepository, never()).delete(any(Video.class));
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("❌ Should return 404 Not Found when video doesn't exist")
        void deleteVideo_FailNotFound() throws Exception {
            Long videoId = 99L;
            given(videoRepository.findById(videoId)).willReturn(Optional.empty());

            mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                    .andExpect(status().isNotFound());

            verify(videoRepository).findById(videoId);
            verifyNoInteractions(videoSecurityService, storageService);
            verify(videoRepository, never()).delete(any(Video.class));
        }

        @Test
        @DisplayName("❌ Should return 401 Unauthorized without token/cookie")
        void deleteVideo_FailUnauthorized() throws Exception {
            mockMvc.perform(delete("/api/videos/{id}", 1L))
                    .andExpect(status().isUnauthorized());
            verifyNoInteractions(videoRepository, videoSecurityService, storageService);
        }
    }
}
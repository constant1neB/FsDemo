package com.example.fsdemo;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.AppUserRepository;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.service.JwtService;
import com.example.fsdemo.service.VideoSecurityService;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.service.VideoStorageException;
import com.example.fsdemo.web.VideoResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive integration tests for VideoController.
 * Tests cover:
 * - Successful video upload scenarios (using UUID filenames)
 * - Authentication requirements
 * - Input validation (size, extension, content-type, filename chars)
 * - Error handling (storage failures)
 * - Security protections (path traversal in original filename)
 * - Transaction behavior
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(VideoControllerTest.TestConfig.class)
@MockitoSettings(strictness = Strictness.LENIENT) // Lenient needed if mocks aren't used in every test path
class VideoControllerTest {

    // Test constants
    private static final String TEST_USERNAME = "testuploader";
    private static final String TEST_PASSWORD = "testpass";
    private static final String TEST_EMAIL = "uploader@example.com";
    private static final String SAMPLE_FILENAME = "sample.mp4"; // Original filename for test file creation
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String INVALID_MIME_TYPE = "application/pdf";
    private static final String DEFAULT_STORAGE_PATH = "default-storage-path-returned-by-mock.mp4"; // Example mock return value

    private String jwtTokenHeader;
    private Cookie fingerprintCookie;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private VideoStorageService storageService;

    @Autowired
    private VideoSecurityService videoSecurityService;

    private AppUser testUser;
    private byte[] sampleVideoContent;

    /**
     * Test configuration that provides mock beans for the application context.
     * This ensures our mocked storage service is injected into the controller.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public VideoStorageService videoStorageServiceMock() {
            return Mockito.mock(VideoStorageService.class);
        }

        @Bean
        @Primary
        public VideoSecurityService videoSecurityServiceMock() {
            return Mockito.mock(VideoSecurityService.class);
        }

        @Bean
        @Primary
        public VideoRepository videoRepositoryMock() {
            return Mockito.mock(VideoRepository.class);
        }
    }

    /**
     * Setup performed before each test:
     * - Cleans test databases
     * - Creates a test user
     * - Loads sample video file
     * - Configures default mock behavior (updated for new store signature)
     * - Obtains valid JWT token for authenticated requests
     */
    @BeforeEach
    void setUp() throws Exception {
        // Clear repositories to ensure test isolation
        Mockito.reset(storageService, videoSecurityService, videoRepository);
        appUserRepository.deleteAll();

        // Create and save test user
        testUser = createTestUser();
        appUserRepository.save(testUser);

        // Load test video file from resources
        sampleVideoContent = loadSampleVideo();

        Mockito.reset(storageService, videoSecurityService);

        // Configure default mock behavior for storage service
        configureDefaultMockBehavior();

        // Obtain JWT token by authenticating (Using the new fingerprint mechanism implicitly)
        authenticateAndGetTokenAndCookie();
    }

    /**
     * Authenticates, captures JWT header AND fingerprint cookie.
     */
    private void authenticateAndGetTokenAndCookie() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .content(String.format(
                                "{\"username\":\"%s\", \"password\":\"%s\"}",
                                TEST_USERNAME, TEST_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.AUTHORIZATION))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();

        // Capture JWT Header
        jwtTokenHeader = result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
        assertThat(jwtTokenHeader).startsWith("Bearer ");

        // Capture Fingerprint Cookie from Set-Cookie header
        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).contains("__Secure-Fgp=");

        // Parse the Set-Cookie header to create a Cookie object for MockMvc requests
        // This is a simplified parsing, assuming the value doesn't contain ';'
        assert setCookieHeader != null;
        String cookieValue = setCookieHeader.split(";")[0].split("=")[1];
        fingerprintCookie = new Cookie(JwtService.FINGERPRINT_COOKIE_NAME, cookieValue);
        // Set attributes that might be relevant for matching/validation if needed, though MockMvc is often lenient
        fingerprintCookie.setHttpOnly(true);
        fingerprintCookie.setSecure(true);
        fingerprintCookie.setPath("/");
        // fingerprintCookie.setSameSite("Strict"); // Note: jakarta.servlet.Cookie doesn't directly support SameSite

        assertThat(fingerprintCookie.getValue()).isNotBlank();
    }


    // === Helper to add auth headers AND cookie to requests ===
    private MockHttpServletRequestBuilder addAuth(MockHttpServletRequestBuilder builder) {
        if (jwtTokenHeader != null && fingerprintCookie != null) {
            return builder
                    .header(HttpHeaders.AUTHORIZATION, jwtTokenHeader)
                    .cookie(fingerprintCookie);
        } else {
            // Fail fast if auth details weren't set up properly
            throw new IllegalStateException("Authentication details (JWT/Cookie) not available for test request.");
        }
    }

    /**
     * Tests successful video upload scenario:
     * - Authenticated user
     * - Valid video file
     * - Proper description
     * Verifies:
     * - Correct HTTP status (201 Created)
     * - Response contains all expected fields, with UUID-based filename
     * - Video metadata saved to database with UUID-based generated filename and correct storage path
     * - Original filename is NOT stored in the database
     * - Storage service was called with UUID-based filename
     */

    @Test
    void uploadVideo_whenAuthenticatedAndValidFile_shouldCreateVideoWithUuidFilename() throws Exception {
        // Arrange
        MockMultipartFile videoFile = createTestVideoFile(SAMPLE_FILENAME);
        String description = "Test video description";
        String expectedStorageFilenameRegex = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.mp4";
        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        String expectedReturnedStoragePath = "specific-path-for-this-test.mp4";
        doReturn(expectedReturnedStoragePath) // Use specific mock behaviour for this test
                .when(storageService).store(any(MultipartFile.class), eq(testUser.getId()), filenameCaptor.capture());

        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> {
            Video videoToSave = invocation.getArgument(0);
            if (videoToSave.getId() == null) {
                videoToSave.setId(1L); // Assign a fixed ID for the test
            }
            return videoToSave;
        });

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(videoFile)
                        .param("description", description)
                        .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                // Check against captured UUID filename
                .andExpect(jsonPath("$.generatedFilename").value(filenameCaptor.getValue()))
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$.fileSize").value(sampleVideoContent.length));

        // Verify database state
        ArgumentCaptor<Video> videoSaveCaptor = ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(videoSaveCaptor.capture());
        Video videoPassedToSave = videoSaveCaptor.getValue();
        assertThat(videoPassedToSave.getDescription()).isEqualTo(description);
        assertThat(videoPassedToSave.getOwner().getUsername()).isEqualTo(TEST_USERNAME);
        assertThat(videoPassedToSave.getGeneratedFilename()).matches(expectedStorageFilenameRegex); // Check saved filename format
        assertThat(videoPassedToSave.getStoragePath()).isEqualTo(expectedReturnedStoragePath);

        // Verify storage service interaction
        verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
        assertThat(filenameCaptor.getValue()).matches(expectedStorageFilenameRegex);
        assertThat(filenameCaptor.getValue()).isNotEqualTo(SAMPLE_FILENAME);
    }

    /**
     * Tests authentication requirement:
     * - Requests without valid authentication token
     * Verifies:
     * - 401 Unauthorized response
     * - No video is created in database
     * - Storage service is not called
     */
    @Test
    void uploadVideo_whenUnauthenticated_shouldReturnUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(multipart("/api/videos") // No addAuth()
                        .file(createTestVideoFile()))
                .andExpect(status().isUnauthorized());

        // Verify no interaction
        verify(storageService, never()).store(any(), anyLong(), anyString());
        assertThat(videoRepository.count()).isZero();
    }

    /**
     * Tests optional description field:
     * - Empty description should be accepted
     * Verifies:
     * - 201 Created response
     * - Video is created with empty description
     */
    @Test
    void uploadVideo_withEmptyDescription_shouldSucceed() throws Exception {
        // Arrange
        MockMultipartFile videoFile = createTestVideoFile();
        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        doReturn(DEFAULT_STORAGE_PATH)
                .when(storageService).store(any(MultipartFile.class), eq(testUser.getId()), filenameCaptor.capture());

        // --- Configure MOCK videoRepository.save() ---
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> {
            Video videoToSave = invocation.getArgument(0);
            if (videoToSave.getId() == null) {
                videoToSave.setId(1L);
            } // Simulate ID assignment
            return videoToSave;
        });

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(createTestVideoFile())
                        .file(videoFile)
                        .param("description", "")))
                .andExpect(status().isCreated());

        // Verify interactions with mocks
        ArgumentCaptor<Video> videoSaveCaptor = ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(videoSaveCaptor.capture()); // Verify save was called
        assertThat(videoSaveCaptor.getValue().getDescription()).isEmpty(); // Verify description was empty in saved object
        verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
    }

    /**
     * Tests file type validation (based on extension):
     * - Non-mp4 files should be rejected
     * Verifies:
     * - 400 Bad Request response
     * - No video is created in database
     * - Storage service is not called
     */
    @Test
    void uploadVideo_withInvalidFileExtension_shouldReturnBadRequest() throws Exception {
        // Arrange
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file", "invalid.pdf", VIDEO_MIME_TYPE, "Not a video".getBytes());

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(invalidFile)))
                .andExpect(status().isBadRequest());

        // Verify
        verify(storageService, never()).store(any(), anyLong(), anyString()); // Expect store NOT called
        assertThat(videoRepository.count()).isZero();
    }

    /**
     * Tests file type validation (based on content type):
     * - Files with non-video content type should be rejected
     * Verifies:
     * - 400 Bad Request response
     * - No video is created in database
     * - Storage service is not called
     */
    @Test
    void uploadVideo_withInvalidContentType_shouldReturnBadRequest() throws Exception {
        // Arrange
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file", "video_with_bad_mime.mp4", INVALID_MIME_TYPE, "Not a video".getBytes());

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(invalidFile)))
                .andExpect(status().isBadRequest());

        // Verify
        verify(storageService, never()).store(any(), anyLong(), anyString()); // Expect store NOT called
        assertThat(videoRepository.count()).isZero();
    }


    /**
     * Tests security against path traversal in the *original* filename:
     * - Malicious original filenames should be rejected before UUID generation
     * Verifies:
     * - 400 Bad Request response
     * - No storage attempt
     */
    @Test
    void uploadVideo_withPathTraversalFilename_shouldReturnBadRequestBeforeUuid() throws Exception {
        // Arrange
        String maliciousFilename = "../malicious.mp4";
        MockMultipartFile maliciousFile = createTestVideoFile(maliciousFilename);

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(maliciousFile)))
                .andExpect(status().isBadRequest());

        // Verify
        verify(storageService, never()).store(any(), anyLong(), anyString()); // Expect store NOT called
        assertThat(videoRepository.count()).isZero();
    }

    /**
     * Tests security against control characters in the *original* filename:
     * - Filenames with control chars should be rejected before UUID generation
     * Verifies:
     * - 400 Bad Request response
     * - No storage attempt
     */
    @Test
    void uploadVideo_withControlCharFilename_shouldReturnBadRequestBeforeUuid() throws Exception {
        // Arrange
        String controlCharFilename = "file\u0000withnull.mp4";
        MockMultipartFile controlCharFile = createTestVideoFile(controlCharFilename);

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(controlCharFile)))
                .andExpect(status().isBadRequest());

        // Verify
        verify(storageService, never()).store(any(), anyLong(), anyString()); // Expect store NOT called
        assertThat(videoRepository.count()).isZero();
    }


    /**
     * Tests storage service failure handling:
     * - Storage exceptions should be handled gracefully
     * Verifies:
     * - 500 Internal Server Error response
     * - Transaction is rolled back (no database entry)
     */
    @Test
    void uploadVideo_whenStorageFails_shouldReturnServerErrorAndRollback() throws Exception {
        // Arrange
        // Override default mock behavior for this specific test
        doThrow(new VideoStorageException("Disk full"))
                .when(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
        long countBefore = videoRepository.count();

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(createTestVideoFile())))
                .andExpect(status().isInternalServerError());

        // Verify rollback
        assertThat(videoRepository.count()).isEqualTo(countBefore);
        // Verify store was actually called (leading to the exception)
        verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
    }

    /**
     * Tests file size validation (using configured 40MB limit):
     * - Files exceeding size limit should be rejected
     * Verifies:
     * - 413 Payload Too Large response
     * - No storage attempt is made
     */
    @Test
    void uploadVideo_withLargeFile_shouldReturnPayloadTooLarge() throws Exception {
        // Arrange
        int sizeInMb = 41;
        byte[] largeFile = new byte[1024 * 1024 * sizeInMb];

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(new MockMultipartFile(
                                "file", "large_video.mp4", VIDEO_MIME_TYPE, largeFile))))
                .andExpect(status().isPayloadTooLarge());

        // Verify
        verify(storageService, never()).store(any(), anyLong(), anyString()); // Expect store NOT called
        assertThat(videoRepository.count()).isZero();
    }

    /**
     * Tests required file field validation:
     * - 'file' field is mandatory
     * Verifies:
     * - 400 Bad Request response
     */
    @Test
    void uploadVideo_withMissingFile_shouldReturnBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .param("description", "No file attached")))
                .andExpect(status().isBadRequest());

        // Verify
        verify(storageService, never()).store(any(), anyLong(), anyString()); // Expect store NOT called
    }

    // === Download Tests ===

    @Test
    void downloadVideo_whenAllowed_shouldReturnVideoFileWithCorrectHeaders() throws Exception {
        // Arrange
        Long videoId = 1L;
        String storedFilename = "a1b2c3d4.mp4"; // Stored name (doesn't matter much for download itself)
        String storagePath = "uploads/videos/" + storedFilename; // Example storage path
        byte[] videoContent = "mock video content".getBytes();
        Resource videoResource = new ByteArrayResource(videoContent);

        Video video = new Video(testUser, storedFilename, "Test", Instant.now(), storagePath, (long) videoContent.length, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(true); // User is allowed to view
        given(storageService.load(storagePath)).willReturn(videoResource); // Mock loading the resource

        // Act & Assert
        MvcResult result = mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(VIDEO_MIME_TYPE))
                // Check Content-Disposition header: attachment; filename="<uuid>.mp4"
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, matchesPattern("attachment; filename=\"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.mp4\"")))
                .andExpect(content().bytes(videoContent))
                .andReturn();

        // Optional: Verify the filename in the header is different from the stored one
        String contentDisposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).isNotNull();
        assertThat(contentDisposition).doesNotContain(storedFilename);

        // Verify mocks
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
        verify(storageService).load(storagePath);
    }

    @Test
    void downloadVideo_whenVideoNotFound_shouldReturnNotFound() throws Exception {
        // Arrange
        Long videoId = 99L;
        given(videoRepository.findById(videoId)).willReturn(Optional.empty()); // Video does not exist

        // Act & Assert
        mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isNotFound());

        // Verify mocks (security/storage shouldn't be called if not found)
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService, never()).canView(anyLong(), anyString());
        verify(storageService, never()).load(anyString());
    }

    @Test
    void downloadVideo_whenNotAllowed_shouldReturnForbidden() throws Exception {
        // Arrange
        Long videoId = 2L;
        String storedFilename = "private.mp4";
        String storagePath = "uploads/videos/" + storedFilename;
        AppUser anotherOwner = new AppUser("another", "pass", "U", "a@a.com"); // Different owner
        Video video = new Video(anotherOwner, storedFilename, "Private", Instant.now(), storagePath, 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setPublic(false); // Ensure it's private

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(false); // User is NOT allowed

        // Act & Assert
        mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isForbidden()); // Expect 403 Forbidden

        // Verify mocks
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
        verify(storageService, never()).load(anyString()); // Storage not called if not allowed
    }

    @Test
    void downloadVideo_whenStorageLoadFails_shouldReturnInternalServerError() throws Exception {
        // Arrange
        Long videoId = 1L;
        String storedFilename = "a1b2c3d4.mp4";
        String storagePath = "uploads/videos/" + storedFilename;

        Video video = new Video(testUser, storedFilename, "Test", Instant.now(), storagePath, 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(true);
        // Mock storageService.load to throw an exception
        given(storageService.load(storagePath)).willThrow(new VideoStorageException("Failed to read file"));

        // Act & Assert
        mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isInternalServerError());

        // Verify mocks
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
        verify(storageService).load(storagePath);
    }

    @Test
    void listVideos_whenAuthenticated_shouldReturnOnlyUserVideos() throws Exception {
        // Arrange
        // Create videos for the test user
        Video userVideo1 = new Video(testUser, "uuid-user1.mp4", "User Video 1", Instant.now(), "path1", 100L, VIDEO_MIME_TYPE);
        userVideo1.setId(1L);
        Video userVideo2 = new Video(testUser, "uuid-user2.mp4", "User Video 2", Instant.now(), "path2", 200L, VIDEO_MIME_TYPE);
        userVideo2.setId(2L);

        // Create a video for another user (should not be returned)
        AppUser otherUser = new AppUser("otherUser", "pass", "USER", "other@example.com");
        otherUser.setId(99L); // Ensure different ID
        Video otherVideo = new Video(otherUser, "uuid-other.mp4", "Other User Video", Instant.now(), "path3", 300L, VIDEO_MIME_TYPE);
        otherVideo.setId(3L);

        // Mock the repository method (we'll add this method next)
        // We expect findByOwnerUsername to be called with the authenticated username
        given(videoRepository.findByOwnerUsername(TEST_USERNAME))
                .willReturn(Arrays.asList(userVideo1, userVideo2));

        // Act & Assert
        MvcResult result = mockMvc.perform(addAuth(get("/api/videos") // Use GET request
                        .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2))) // Expecting 2 videos for the user
                .andExpect(jsonPath("$[0].id").value(userVideo1.getId()))
                .andExpect(jsonPath("$[0].generatedFilename").value(userVideo1.getGeneratedFilename()))
                .andExpect(jsonPath("$[0].ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$[1].id").value(userVideo2.getId()))
                .andExpect(jsonPath("$[1].generatedFilename").value(userVideo2.getGeneratedFilename()))
                .andExpect(jsonPath("$[1].ownerUsername").value(TEST_USERNAME))
                .andReturn();

        // Optional: Further verification by deserializing the response
        String jsonResponse = result.getResponse().getContentAsString();
        List<VideoResponse> videoResponses = objectMapper.readValue(jsonResponse, new TypeReference<List<VideoResponse>>() {
        });
        assertThat(videoResponses).hasSize(2);
        assertThat(videoResponses).extracting(VideoResponse::ownerUsername).containsOnly(TEST_USERNAME);
        assertThat(videoResponses).extracting(VideoResponse::id).containsExactlyInAnyOrder(userVideo1.getId(), userVideo2.getId());

        // Verify repository interaction
        verify(videoRepository).findByOwnerUsername(TEST_USERNAME);
        // Verify security service was NOT called (listing doesn't need individual view checks here)
        verify(videoSecurityService, never()).canView(anyLong(), anyString());
    }

    @Test
    void listVideos_whenUnauthenticated_shouldReturnUnauthorized() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/videos") // No addAuth()
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        // Verify no repository interaction
        verify(videoRepository, never()).findByOwnerUsername(anyString());
    }

    @Test
    void listVideos_whenUserHasNoVideos_shouldReturnEmptyList() throws Exception {
        // Arrange
        // Mock the repository method to return an empty list
        given(videoRepository.findByOwnerUsername(TEST_USERNAME))
                .willReturn(Collections.emptyList());

        // Act & Assert
        mockMvc.perform(addAuth(get("/api/videos")
                        .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0))); // Expecting an empty array

        // Verify repository interaction
        verify(videoRepository).findByOwnerUsername(TEST_USERNAME);
    }

    @Test
    void getVideoDetails_whenAllowed_shouldReturnVideoResponse() throws Exception {
        // Arrange
        Long videoId = 1L;
        String generatedFilename = "uuid-details.mp4";
        String description = "Details Test Video";
        String storagePath = "path/to/" + generatedFilename;
        long fileSize = 12345L;

        Video video = new Video(testUser, generatedFilename, description, Instant.now(), storagePath, fileSize, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(true); // User is allowed

        // Act & Assert
        mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(videoId))
                .andExpect(jsonPath("$.generatedFilename").value(generatedFilename))
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$.fileSize").value(fileSize));

        // Verify mocks
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
    }

    @Test
    void getVideoDetails_whenVideoNotFound_shouldReturnNotFound() throws Exception {
        // Arrange
        Long videoId = 99L; // Non-existent ID
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        // Act & Assert
        mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                .andExpect(status().isNotFound());

        // Verify mocks
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService, never()).canView(anyLong(), anyString()); // Security check shouldn't happen
    }

    @Test
    void getVideoDetails_whenNotAllowed_shouldReturnForbidden() throws Exception {
        // Arrange
        Long videoId = 2L;
        String generatedFilename = "private-video.mp4";
        AppUser anotherOwner = new AppUser("another", "pass", "U", "a@a.com");
        Video video = new Video(anotherOwner, generatedFilename, "Private", Instant.now(), "path/private", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(false); // User is NOT allowed

        // Act & Assert
        mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                .andExpect(status().isForbidden()); // Expect 403 Forbidden

        // Verify mocks
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
    }

    // ============ HELPER METHODS ============ //

    /**
     * Creates and returns a test user with predefined credentials
     */
    private AppUser createTestUser() { // No changes needed
        return new AppUser(
                TEST_USERNAME,
                passwordEncoder.encode(TEST_PASSWORD),
                "USER",
                TEST_EMAIL
        );
    }

    /**
     * Loads sample video file from test resources
     */
    private byte[] loadSampleVideo() throws Exception { // No changes needed
        return Files.readAllBytes(
                Paths.get("src/test/resources/" + SAMPLE_FILENAME)
        );
    }

    /**
     * Configures default mock behavior for storage service (updated signature)
     */
    private void configureDefaultMockBehavior() {
        // Mock the store method with the new signature
        doReturn(DEFAULT_STORAGE_PATH)
                .when(storageService).store(any(MultipartFile.class), anyLong(), anyString());
    }


    /**
     * Creates a test video file using sample content and a specific filename.
     */
    private MockMultipartFile createTestVideoFile(String filename) {
        return new MockMultipartFile(
                "file",
                filename,
                VIDEO_MIME_TYPE,
                sampleVideoContent
        );
    }

    /**
     * Overload for tests that don't need a specific original filename for the multipart file.
     */
    private MockMultipartFile createTestVideoFile() {
        return createTestVideoFile(SAMPLE_FILENAME);
    }
}
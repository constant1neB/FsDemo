package com.example.fsdemo;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.AppUserRepository;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.service.JwtService;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.service.VideoStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;


import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern; // For UUID regex matching
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString; // Import anyString
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private VideoRepository videoRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;


    @Autowired
    private VideoStorageService storageService;

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
        public VideoStorageService videoStorageService() {
            return Mockito.mock(VideoStorageService.class);
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
        videoRepository.deleteAll();
        appUserRepository.deleteAll();

        // Create and save test user
        testUser = createTestUser();
        appUserRepository.save(testUser);

        // Load test video file from resources
        sampleVideoContent = loadSampleVideo();

        Mockito.reset(storageService);

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

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(videoFile)
                        .param("description", description)
                        .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.generatedFilename", matchesPattern(expectedStorageFilenameRegex)))
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$.fileSize").value(sampleVideoContent.length));

        // Verify database state
        assertThat(videoRepository.count()).isEqualTo(1);
        Video savedVideo = videoRepository.findAll().get(0);
        assertThat(savedVideo.getGeneratedFilename()).isNotEqualTo(SAMPLE_FILENAME);
        assertThat(savedVideo.getGeneratedFilename()).matches(expectedStorageFilenameRegex);
        assertThat(savedVideo.getStoragePath()).isEqualTo(expectedReturnedStoragePath);
        assertThat(savedVideo.getDescription()).isEqualTo(description);
        assertThat(savedVideo.getOwner().getId()).isEqualTo(testUser.getId());
        assertThat(savedVideo.getFileSize()).isEqualTo(sampleVideoContent.length);

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
        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(createTestVideoFile())
                        .param("description", "")))
                .andExpect(status().isCreated());

        // Verify
        assertThat(videoRepository.count()).isEqualTo(1);
        Video savedVideo = videoRepository.findAll().get(0);
        assertThat(savedVideo.getDescription()).isEmpty();
        verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString()); // Verify store *was* called
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
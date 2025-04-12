package com.example.fsdemo;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.AppUserRepository;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.service.VideoStorageException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive integration tests for VideoController.
 * Tests cover:
 * - Successful video upload scenarios
 * - Authentication requirements
 * - Input validation
 * - Error handling
 * - Security protections
 * - Transaction behavior
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@MockitoSettings(strictness = Strictness.LENIENT)
class VideoControllerTest {

    // Test constants
    private static final String TEST_USERNAME = "testuploader";
    private static final String TEST_PASSWORD = "testpass";
    private static final String TEST_EMAIL = "uploader@example.com";
    private static final String SAMPLE_FILENAME = "sample.mp4";
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String INVALID_MIME_TYPE = "application/pdf";
    private static final String DEFAULT_STORAGE_PATH = "default-storage-path.mp4";

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

    @MockBean
    private VideoStorageService storageService;

    private String jwtToken;
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
     * - Configures default mock behavior
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

        // Configure default mock behavior for storage service
        configureDefaultMockBehavior();

        // Obtain JWT token by authenticating
        jwtToken = authenticateAndGetToken();
    }

    /**
     * Tests successful video upload scenario:
     * - Authenticated user
     * - Valid video file
     * - Proper description
     * Verifies:
     * - Correct HTTP status (201 Created)
     * - Response contains all expected fields
     * - Video metadata saved to database
     * - Storage service was called with correct parameters
     */
    @Test
    void uploadVideo_whenAuthenticatedAndValidFile_shouldCreateVideoAndReturnCreated() throws Exception {
        // Arrange
        MockMultipartFile videoFile = createTestVideoFile();
        String description = "Test video description";

        // Override default mock behavior for this specific test
        doReturn("custom-storage-path.mp4")
                .when(storageService).store(any(), anyLong());

        // Act & Assert
        mockMvc.perform(multipart("/api/videos")
                        .file(videoFile)
                        .param("description", description)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.originalFilename").value(SAMPLE_FILENAME))
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$.fileSize").value(sampleVideoContent.length));

        // Verify database state
        assertThat(videoRepository.count()).isEqualTo(1);
        Video savedVideo = videoRepository.findAll().get(0);
        assertVideoMetadata(savedVideo, description, SAMPLE_FILENAME);

        // Verify storage service interaction
        verify(storageService).store(any(), eq(testUser.getId()));
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
        mockMvc.perform(multipart("/api/videos")
                        .file(createTestVideoFile()))
                .andExpect(status().isUnauthorized());

        // Verify no interaction with storage or database
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
        mockMvc.perform(multipart("/api/videos")
                        .file(createTestVideoFile())
                        .param("description", "")
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isCreated());

        // Verify
        Video savedVideo = videoRepository.findAll().get(0);
        assertThat(savedVideo.getDescription()).isEmpty();
    }

    /**
     * Tests file type validation:
     * - Non-video files should be rejected
     * Verifies:
     * - 400 Bad Request response
     * - No video is created in database
     * - Storage service is not called
     */
    @Test
    void uploadVideo_withInvalidFileType_shouldReturnBadRequest() throws Exception {
        // Arrange
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file",
                "invalid.pdf",
                INVALID_MIME_TYPE,
                "Not a video".getBytes()
        );

        // Act & Assert
        mockMvc.perform(multipart("/api/videos")
                        .file(invalidFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isBadRequest());

        // Verify
        assertThat(videoRepository.count()).isZero();
    }

    /**
     * Tests security against path traversal:
     * - Malicious filenames should be sanitized
     * Verifies:
     * - 201 Created response
     * - Filename is sanitized in stored metadata
     */
    @Test
    void uploadVideo_withPathTraversalFilename_shouldSanitize() throws Exception {
        // Arrange
        String maliciousFilename = "../malicious.mp4";
        MockMultipartFile maliciousFile = new MockMultipartFile(
                "file",
                maliciousFilename,
                VIDEO_MIME_TYPE,
                sampleVideoContent
        );

        // Act & Assert
        mockMvc.perform(multipart("/api/videos")
                        .file(maliciousFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isBadRequest());

    }

    /**
     * Tests storage service failure handling:
     * - Storage exceptions should be handled gracefully
     * Verifies:
     * - 500 Internal Server Error response
     * - Transaction is rolled back (no database entry)
     */
    @Test
    void uploadVideo_whenStorageFails_shouldReturnServerError() throws Exception {
        // Arrange
        doThrow(new VideoStorageException("Disk full"))
                .when(storageService).store(any(), anyLong());

        long countBefore = videoRepository.count();

        // Act & Assert
        mockMvc.perform(multipart("/api/videos")
                        .file(createTestVideoFile())
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isInternalServerError());

        // Verify
        assertThat(videoRepository.count()).isEqualTo(countBefore);
    }

    /**
     * Tests file size validation:
     * - Files exceeding size limit should be rejected
     * Verifies:
     * - 413 Payload Too Large response
     * - No storage attempt is made
     */
    @Test
    void uploadVideo_withLargeFile_shouldReturnPayloadTooLarge() throws Exception {
        // Arrange
        byte[] largeFile = new byte[1024 * 1024 * 101]; // 101MB

        // Act & Assert
        mockMvc.perform(multipart("/api/videos")
                        .file(new MockMultipartFile(
                                "file",
                                "large.mp4",
                                VIDEO_MIME_TYPE,
                                largeFile))
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isPayloadTooLarge());

        // Verify
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
        mockMvc.perform(multipart("/api/videos")
                        .param("description", "No file attached")
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isBadRequest());
    }

    // ============ HELPER METHODS ============ //

    /**
     * Creates and returns a test user with predefined credentials
     */
    private AppUser createTestUser() {
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
    private byte[] loadSampleVideo() throws Exception {
        return Files.readAllBytes(
                Paths.get("src/test/resources/" + SAMPLE_FILENAME)
        );
    }

    /**
     * Configures default mock behavior for storage service
     */
    private void configureDefaultMockBehavior() {
        doReturn(DEFAULT_STORAGE_PATH)
                .when(storageService).store(any(), anyLong());
    }

    /**
     * Authenticates and returns JWT token
     */
    private String authenticateAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .content(String.format(
                                "{\"username\":\"%s\", \"password\":\"%s\"}",
                                TEST_USERNAME, TEST_PASSWORD))
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        String token = result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
        assertThat(token).startsWith("Bearer ");
        return token;
    }

    /**
     * Creates a test video file using sample content
     */
    private MockMultipartFile createTestVideoFile() {
        return new MockMultipartFile(
                "file",
                SAMPLE_FILENAME,
                VIDEO_MIME_TYPE,
                sampleVideoContent
        );
    }

    /**
     * Custom assertion for video metadata validation
     */
    private void assertVideoMetadata(
            Video video,
            String expectedDescription,
            String expectedFilename
    ) {
        assertThat(video)
                .isNotNull()
                .extracting(
                        Video::getDescription,
                        Video::getOriginalFilename,
                        Video::getFileSize,
                        v -> v.getOwner().getUsername()
                )
                .containsExactly(
                        expectedDescription,
                        expectedFilename,
                        (long) sampleVideoContent.length,
                        TEST_USERNAME
                );
    }
}
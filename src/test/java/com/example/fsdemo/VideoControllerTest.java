package com.example.fsdemo;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.AppUserRepository;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.service.*;
import com.example.fsdemo.web.EditOptions;
import com.example.fsdemo.web.UpdateVideoRequest;
import com.example.fsdemo.web.VideoResponse;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
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

/**
 * Comprehensive integration tests for VideoController.
 * Tests cover:
 * - Successful video upload scenarios (using UUID filenames)
 * - Authentication requirements
 * - Input validation (size, extension, content-type, filename chars)
 * - Error handling (storage failures, processing errors)
 * - Security protections (path traversal, ownership checks)
 * - Asynchronous processing initiation
 * - Transaction behavior
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// No explicit @Import needed if using @MockitoBean for all mocks
@MockitoSettings(strictness = Strictness.LENIENT) // Lenient might be needed if not all mocks used in every path
class VideoControllerTest {

    // Test constants
    private static final String TEST_USERNAME = "testuploader";
    private static final String TEST_PASSWORD = "testpass";
    private static final String TEST_EMAIL = "uploader@example.com";
    private static final String SAMPLE_FILENAME = "sample.mp4"; // Original filename for test file creation
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String INVALID_MIME_TYPE = "application/pdf";
    private static final String DEFAULT_STORAGE_PATH = "default-storage-path-returned-by-mock.mp4"; // Example mock return value

    // Autowired beans from the test context
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    // --- Use @MockitoBean for mocks required by the controller ---
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

    // Test state variables
    private AppUser testUser;
    private String jwtTokenHeader;
    private Cookie fingerprintCookie;
    private byte[] sampleVideoContent;

    // No need for TestConfig if all mocks are handled by @MockitoBean
    // @TestConfiguration
    // static class TestConfig { ... }

    @BeforeEach
    void setUp() throws Exception {
        // --- Reset mocks before each test ---
        Mockito.reset(storageService, videoSecurityService, videoRepository, videoProcessingService, appUserRepository);

        // Create test user object
        testUser = createTestUser(); // Uses Autowired passwordEncoder

        // --- Mock AppUserRepository interactions ---
        given(appUserRepository.findByUsername(TEST_USERNAME)).willReturn(Optional.of(testUser));
        // Mock save if needed by other logic (e.g., if user creation happened in controller)
        // given(appUserRepository.save(any(AppUser.class))).willReturn(testUser);

        // Load test video file content
        sampleVideoContent = loadSampleVideo();

        // Configure default mock behavior for storage service's store method (can be overridden in tests)
        configureDefaultMockBehavior();

        // Obtain JWT token by authenticating (requires mocked AppUserRepository)
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

        jwtTokenHeader = result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
        assertThat(jwtTokenHeader).startsWith("Bearer ");

        String setCookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookieHeader).contains(JwtService.FINGERPRINT_COOKIE_NAME + "=");

        assert setCookieHeader != null;
        String cookieValue = setCookieHeader.split(";")[0].split("=")[1];
        fingerprintCookie = new Cookie(JwtService.FINGERPRINT_COOKIE_NAME, cookieValue);
        fingerprintCookie.setHttpOnly(true);
        fingerprintCookie.setSecure(true);
        fingerprintCookie.setPath("/");

        assertThat(fingerprintCookie.getValue()).isNotBlank();
    }


    // === Helper to add auth headers AND cookie to requests ===
    private MockHttpServletRequestBuilder addAuth(MockHttpServletRequestBuilder builder) {
        if (jwtTokenHeader != null && fingerprintCookie != null) {
            return builder
                    .header(HttpHeaders.AUTHORIZATION, jwtTokenHeader)
                    .cookie(fingerprintCookie);
        } else {
            throw new IllegalStateException("Authentication details (JWT/Cookie) not available for test request.");
        }
    }

    // ============ UPLOAD TESTS ============

    @Test
    void uploadVideo_whenAuthenticatedAndValidFile_shouldCreateVideoWithUuidFilename() throws Exception {
        // Arrange
        MockMultipartFile videoFile = createTestVideoFile(SAMPLE_FILENAME);
        String description = "Test video description";
        String expectedStorageFilenameRegex = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.mp4";

        // Capture the filename passed to storage service
        ArgumentCaptor<String> filenameCaptor = ArgumentCaptor.forClass(String.class);
        String expectedReturnedStoragePath = "specific-path-for-this-test.mp4";
        doReturn(expectedReturnedStoragePath)
                .when(storageService).store(any(MultipartFile.class), eq(testUser.getId()), filenameCaptor.capture());

        // Capture the video saved to the repository
        ArgumentCaptor<Video> videoSaveCaptor = ArgumentCaptor.forClass(Video.class);
        // Mock the save operation to simulate ID generation and return the saved object
        given(videoRepository.save(videoSaveCaptor.capture())).willAnswer(invocation -> {
            Video videoToSave = invocation.getArgument(0);
            videoToSave.setId(1L); // Assign a predictable ID for the response
            return videoToSave;
        });

        // Act & Assert
        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(videoFile)
                        .param("description", description)
                        .accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isCreated())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1L)) // Check the assigned ID
                .andExpect(jsonPath("$.generatedFilename").value(filenameCaptor.getValue())) // Check generated filename in response
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$.fileSize").value(sampleVideoContent.length));

        // Verify captured values after the request
        Video savedVideo = videoSaveCaptor.getValue();
        assertThat(savedVideo.getDescription()).isEqualTo(description);
        assertThat(savedVideo.getOwner().getUsername()).isEqualTo(TEST_USERNAME);
        assertThat(savedVideo.getGeneratedFilename()).matches(expectedStorageFilenameRegex); // Check filename format saved
        assertThat(savedVideo.getStoragePath()).isEqualTo(expectedReturnedStoragePath); // Check storage path saved
        assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.UPLOADED); // Check initial status

        // Verify storage service interaction
        // store(MultipartFile file, Long userId, String generatedFilename)
        verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), eq(filenameCaptor.getValue()));
        assertThat(filenameCaptor.getValue()).matches(expectedStorageFilenameRegex); // Verify captured filename format
        assertThat(filenameCaptor.getValue()).isNotEqualTo(SAMPLE_FILENAME); // Ensure original wasn't used
    }

    @Test
    void uploadVideo_whenUnauthenticated_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(multipart("/api/videos").file(createTestVideoFile()))
                .andExpect(status().isUnauthorized());

        verify(storageService, never()).store(any(), anyLong(), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void uploadVideo_withEmptyDescription_shouldSucceed() throws Exception {
        MockMultipartFile videoFile = createTestVideoFile();
        ArgumentCaptor<Video> videoSaveCaptor = ArgumentCaptor.forClass(Video.class);

        given(videoRepository.save(videoSaveCaptor.capture())).willAnswer(invocation -> {
            Video videoToSave = invocation.getArgument(0);
            videoToSave.setId(1L);
            return videoToSave;
        });
        // Use default mock behavior for storageService.store

        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(videoFile)
                        .param("description", ""))) // Empty description
                .andExpect(status().isCreated());

        assertThat(videoSaveCaptor.getValue().getDescription()).isEmpty();
        verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
    }

    @Test
    void uploadVideo_withInvalidFileExtension_shouldReturnBadRequest() throws Exception {
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file", "invalid.txt", VIDEO_MIME_TYPE, "content".getBytes());

        mockMvc.perform(addAuth(multipart("/api/videos").file(invalidFile)))
                .andExpect(status().isBadRequest());

        verify(storageService, never()).store(any(), anyLong(), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void uploadVideo_withInvalidContentType_shouldReturnBadRequest() throws Exception {
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file", "video_with_bad_mime.mp4", INVALID_MIME_TYPE, "content".getBytes());

        mockMvc.perform(addAuth(multipart("/api/videos").file(invalidFile)))
                .andExpect(status().isBadRequest());

        verify(storageService, never()).store(any(), anyLong(), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void uploadVideo_withPathTraversalFilename_shouldReturnBadRequestBeforeUuid() throws Exception {
        MockMultipartFile maliciousFile = createTestVideoFile("../malicious.mp4");

        mockMvc.perform(addAuth(multipart("/api/videos").file(maliciousFile)))
                .andExpect(status().isBadRequest());

        verify(storageService, never()).store(any(), anyLong(), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void uploadVideo_withControlCharFilename_shouldReturnBadRequestBeforeUuid() throws Exception {
        MockMultipartFile controlCharFile = createTestVideoFile("file\u0000withnull.mp4");

        mockMvc.perform(addAuth(multipart("/api/videos").file(controlCharFile)))
                .andExpect(status().isBadRequest());

        verify(storageService, never()).store(any(), anyLong(), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void uploadVideo_whenStorageFails_shouldReturnServerErrorAndRollback() throws Exception {
        // Override default mock behavior for storage service
        doThrow(new VideoStorageException("Disk full"))
                .when(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());

        mockMvc.perform(addAuth(multipart("/api/videos").file(createTestVideoFile())))
                .andExpect(status().isInternalServerError());

        // Verify store was called (leading to the exception)
        verify(storageService).store(any(MultipartFile.class), eq(testUser.getId()), anyString());
        // Verify repository save was NOT called due to the exception before it
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void uploadVideo_withLargeFile_shouldReturnPayloadTooLarge() throws Exception {
        byte[] largeFile = new byte[1024 * 1024 * 41]; // 41MB (assuming default limit is 40MB)

        mockMvc.perform(addAuth(multipart("/api/videos")
                        .file(new MockMultipartFile("file", "large_video.mp4", VIDEO_MIME_TYPE, largeFile))))
                .andExpect(status().isPayloadTooLarge());

        verify(storageService, never()).store(any(), anyLong(), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void uploadVideo_withMissingFile_shouldReturnBadRequest() throws Exception {
        mockMvc.perform(addAuth(multipart("/api/videos").param("description", "No file")))
                .andExpect(status().isBadRequest());

        verify(storageService, never()).store(any(), anyLong(), anyString());
    }

    // ============ DOWNLOAD TESTS ============

    @Test
    void downloadVideo_whenAllowed_shouldReturnVideoFileWithCorrectHeaders() throws Exception {
        // Arrange
        Long videoId = 1L;
        String storedFilename = "a1b2c3d4.mp4";
        String storagePath = "uploads/videos/" + storedFilename;
        byte[] videoContent = "mock video content".getBytes();
        Resource videoResource = new ByteArrayResource(videoContent);

        Video video = new Video(testUser, storedFilename, "Test", Instant.now(), storagePath, (long) videoContent.length, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(true);
        given(storageService.load(storagePath)).willReturn(videoResource);

        // Act & Assert
        MvcResult result = mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(VIDEO_MIME_TYPE))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, matchesPattern("attachment; filename=\"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.mp4\"")))
                .andExpect(content().bytes(videoContent))
                .andReturn();

        String contentDisposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).isNotNull().doesNotContain(storedFilename);

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
        verify(storageService).load(storagePath);
    }

    @Test
    void downloadVideo_whenVideoNotFound_shouldReturnNotFound() throws Exception {
        Long videoId = 99L;
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isNotFound());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService, never()).canView(anyLong(), anyString());
        verify(storageService, never()).load(anyString());
    }

    @Test
    void downloadVideo_whenNotAllowed_shouldReturnForbidden() throws Exception {
        Long videoId = 2L;
        AppUser anotherOwner = new AppUser("another", "pass", "U", "a@a.com");
        anotherOwner.setId(99L);
        Video video = new Video(anotherOwner, "private.mp4", "Private", Instant.now(), "path/private", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setPublic(false);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(false);

        mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isForbidden());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
        verify(storageService, never()).load(anyString());
    }

    @Test
    void downloadVideo_whenStorageLoadFails_shouldReturnInternalServerError() throws Exception {
        Long videoId = 1L;
        String storagePath = "uploads/videos/a1b2c3d4.mp4";
        Video video = new Video(testUser, "a1b2c3d4.mp4", "Test", Instant.now(), storagePath, 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(true);
        given(storageService.load(storagePath)).willThrow(new VideoStorageException("Failed to read file"));

        mockMvc.perform(addAuth(get("/api/videos/{id}/download", videoId)))
                .andExpect(status().isInternalServerError());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
        verify(storageService).load(storagePath);
    }

    // ============ LIST VIDEOS TESTS ============

    @Test
    void listVideos_whenAuthenticated_shouldReturnOnlyUserVideos() throws Exception {
        // Arrange
        Video userVideo1 = new Video(testUser, "uuid-user1.mp4", "User Video 1", Instant.now(), "path1", 100L, VIDEO_MIME_TYPE);
        userVideo1.setId(1L);
        Video userVideo2 = new Video(testUser, "uuid-user2.mp4", "User Video 2", Instant.now(), "path2", 200L, VIDEO_MIME_TYPE);
        userVideo2.setId(2L);
        // No need to create other user's video as the mock controls the result

        given(videoRepository.findByOwnerUsername(TEST_USERNAME))
                .willReturn(Arrays.asList(userVideo1, userVideo2));

        // Act & Assert
        MvcResult result = mockMvc.perform(addAuth(get("/api/videos").accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(userVideo1.getId()))
                .andExpect(jsonPath("$[0].ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$[1].id").value(userVideo2.getId()))
                .andExpect(jsonPath("$[1].ownerUsername").value(TEST_USERNAME))
                .andReturn();

        // Verify response DTO mapping
        List<VideoResponse> videoResponses = objectMapper.readValue(
                result.getResponse().getContentAsString(), new TypeReference<>() {
                });
        assertThat(videoResponses).hasSize(2);
        assertThat(videoResponses).extracting(VideoResponse::ownerUsername).containsOnly(TEST_USERNAME);
        assertThat(videoResponses).extracting(VideoResponse::id).containsExactlyInAnyOrder(1L, 2L);

        verify(videoRepository).findByOwnerUsername(TEST_USERNAME);
    }

    @Test
    void listVideos_whenUnauthenticated_shouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/videos").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());

        verify(videoRepository, never()).findByOwnerUsername(anyString());
    }

    @Test
    void listVideos_whenUserHasNoVideos_shouldReturnEmptyList() throws Exception {
        given(videoRepository.findByOwnerUsername(TEST_USERNAME)).willReturn(Collections.emptyList());

        mockMvc.perform(addAuth(get("/api/videos").accept(MediaType.APPLICATION_JSON)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(0)));

        verify(videoRepository).findByOwnerUsername(TEST_USERNAME);
    }

    // ============ GET DETAILS TESTS ============

    @Test
    void getVideoDetails_whenAllowed_shouldReturnVideoResponse() throws Exception {
        Long videoId = 1L;
        String generatedFilename = "uuid-details.mp4";
        Video video = new Video(testUser, generatedFilename, "Details", Instant.now(), "path/details", 123L, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(true);

        mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(videoId))
                .andExpect(jsonPath("$.generatedFilename").value(generatedFilename))
                .andExpect(jsonPath("$.description").value("Details"))
                .andExpect(jsonPath("$.ownerUsername").value(TEST_USERNAME))
                .andExpect(jsonPath("$.fileSize").value(123L));

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
    }

    @Test
    void getVideoDetails_whenVideoNotFound_shouldReturnNotFound() throws Exception {
        Long videoId = 99L;
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                .andExpect(status().isNotFound());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService, never()).canView(anyLong(), anyString());
    }

    @Test
    void getVideoDetails_whenNotAllowed_shouldReturnForbidden() throws Exception {
        Long videoId = 2L;
        AppUser anotherOwner = new AppUser("another", "pass", "U", "a@a.com");
        anotherOwner.setId(99L);
        Video video = new Video(anotherOwner, "private-video.mp4", "Private", Instant.now(), "path/private", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canView(videoId, TEST_USERNAME)).willReturn(false);

        mockMvc.perform(addAuth(get("/api/videos/{id}", videoId)))
                .andExpect(status().isForbidden());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canView(videoId, TEST_USERNAME);
    }

    // ============ UPDATE DESCRIPTION TESTS ============

    @ParameterizedTest
    @ValueSource(strings = {
            "Invalid <script>alert('XSS')</script> tags",
            "Contains symbols like $#@%^&*",
            "Line breaks\n are not allowed",
            "Tabs\t are not allowed",
            "Starts with invalid > character",
            "Ends with invalid < character"
    })
    void updateVideoDescription_withInvalidCharacters_shouldReturnBadRequest(String invalidDescription) throws Exception {
        Long videoId = 1L;
        Video originalVideo = new Video(testUser, "invalid-desc.mp4", "Old", Instant.now(), "path/invalid", 100L, VIDEO_MIME_TYPE);
        originalVideo.setId(videoId);
        // Mock find but not save, as validation should fail first
        given(videoRepository.findById(videoId)).willReturn(Optional.of(originalVideo));
        given(videoSecurityService.isOwner(videoId, TEST_USERNAME)).willReturn(true);

        UpdateVideoRequest updateRequest = new UpdateVideoRequest(invalidDescription);

        mockMvc.perform(addAuth(put("/api/videos/{id}", videoId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))))
                .andExpect(status().isBadRequest());

        verify(videoRepository, never()).save(any(Video.class));
    }

    // Add test for successful description update if needed

    // ============ DELETE TESTS ============

    @Test
    void deleteVideo_whenOwner_shouldDeleteFromStorageAndDbAndReturnNoContent() throws Exception {
        Long videoId = 1L;
        String storagePath = "path/to/delete/video.mp4";
        Video videoToDelete = new Video(testUser, "delete-uuid.mp4", "To Delete", Instant.now(), storagePath, 100L, VIDEO_MIME_TYPE);
        videoToDelete.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(videoToDelete));
        given(videoSecurityService.canDelete(videoId, TEST_USERNAME)).willReturn(true);
        doNothing().when(storageService).delete(storagePath);
        doNothing().when(videoRepository).delete(videoToDelete); // Mock void DB delete

        mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                .andExpect(status().isNoContent());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canDelete(videoId, TEST_USERNAME);
        verify(storageService).delete(storagePath);
        verify(videoRepository).delete(videoToDelete);
    }

    @Test
    void deleteVideo_whenVideoNotFound_shouldReturnNotFound() throws Exception {
        Long videoId = 99L;
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                .andExpect(status().isNotFound());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService, never()).canDelete(anyLong(), anyString());
        verify(storageService, never()).delete(anyString());
        verify(videoRepository, never()).delete(any(Video.class));
    }

    @Test
    void deleteVideo_whenNotOwner_shouldReturnForbidden() throws Exception {
        Long videoId = 2L;
        AppUser anotherOwner = new AppUser("another", "pass", "U", "a@a.com");
        anotherOwner.setId(99L);
        Video video = new Video(anotherOwner, "other-del.mp4", "Other Desc", Instant.now(), "path/other-del", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.canDelete(videoId, TEST_USERNAME)).willReturn(false);

        mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                .andExpect(status().isForbidden());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canDelete(videoId, TEST_USERNAME);
        verify(storageService, never()).delete(anyString());
        verify(videoRepository, never()).delete(any(Video.class));
    }

    @Test
    void deleteVideo_whenStorageDeleteFails_shouldReturnInternalServerErrorAndNotDeleteFromDb() throws Exception {
        Long videoId = 1L;
        String storagePath = "path/to/fail/delete.mp4";
        Video videoToDelete = new Video(testUser, "fail-delete-uuid.mp4", "Fail Delete", Instant.now(), storagePath, 100L, VIDEO_MIME_TYPE);
        videoToDelete.setId(videoId);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(videoToDelete));
        given(videoSecurityService.canDelete(videoId, TEST_USERNAME)).willReturn(true);
        // Mock storageService.delete to throw exception
        doThrow(new VideoStorageException("Disk I/O error during delete"))
                .when(storageService).delete(storagePath);

        mockMvc.perform(addAuth(delete("/api/videos/{id}", videoId)))
                .andExpect(status().isInternalServerError());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).canDelete(videoId, TEST_USERNAME);
        verify(storageService).delete(storagePath); // Storage delete was attempted
        // Verify DB delete was NOT called due to exception and @Transactional rollback
        verify(videoRepository, never()).delete(any(Video.class));
    }

    // ============ PROCESSING ENDPOINT TESTS ============

    @Test
    void processVideo_whenValidRequestAndOwnerAndStatusReady_shouldReturnAcceptedAndUpdateStatusAndCallService() throws Exception {
        // Arrange
        Long videoId = 1L;
        Video video = new Video(testUser, "process-test.mp4", "Desc", Instant.now(), "path/process", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setStatus(VideoStatus.READY);

        EditOptions validOptions = new EditOptions(null, null, false, 720);
        String requestBody = objectMapper.writeValueAsString(validOptions);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(videoProcessingService).processVideoEdits(anyLong(), any(EditOptions.class), anyString());

        // Act
        mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());

        // Assert
        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).isOwner(videoId, testUser.getUsername());

        ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(videoCaptor.capture());
        assertThat(videoCaptor.getValue().getStatus()).isEqualTo(VideoStatus.PROCESSING);
        assertThat(videoCaptor.getValue().getProcessedStoragePath()).isNull();

        verify(videoProcessingService).processVideoEdits(eq(videoId), eq(validOptions), eq(testUser.getUsername()));
    }

    @Test
    void processVideo_whenValidRequestAndOwnerAndStatusUploaded_shouldReturnAcceptedAndUpdateStatusAndCallService() throws Exception {
        Long videoId = 2L;
        Video video = new Video(testUser, "process-uploaded.mp4", "Desc", Instant.now(), "path/uploaded", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setStatus(VideoStatus.UPLOADED);

        EditOptions validOptions = new EditOptions(10.5, 20.0, true, null);
        String requestBody = objectMapper.writeValueAsString(validOptions);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));
        doNothing().when(videoProcessingService).processVideoEdits(anyLong(), any(EditOptions.class), anyString());

        mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
        ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
        verify(videoRepository).save(videoCaptor.capture());
        assertThat(videoCaptor.getValue().getStatus()).isEqualTo(VideoStatus.PROCESSING);
        verify(videoProcessingService).processVideoEdits(eq(videoId), eq(validOptions), eq(testUser.getUsername()));
    }

    @Test
    void processVideo_whenNotOwner_shouldReturnForbidden() throws Exception {
        Long videoId = 3L;
        AppUser realOwner = new AppUser("realOwner", "pass", "U", "r@r.com");
        realOwner.setId(99L);
        Video video = new Video(realOwner, "forbidden.mp4", "Desc", Instant.now(), "path/forbidden", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setStatus(VideoStatus.UPLOADED);

        EditOptions validOptions = new EditOptions(null, null, false, 720);
        String requestBody = objectMapper.writeValueAsString(validOptions);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(false);

        mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
        verify(videoProcessingService, never()).processVideoEdits(anyLong(), any(EditOptions.class), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void processVideo_whenVideoNotFound_shouldReturnNotFound() throws Exception {
        Long videoId = 99L;
        EditOptions validOptions = new EditOptions(null, null, false, 720);
        String requestBody = objectMapper.writeValueAsString(validOptions);

        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService, never()).isOwner(anyLong(), anyString());
        verify(videoProcessingService, never()).processVideoEdits(anyLong(), any(EditOptions.class), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void processVideo_whenAlreadyProcessing_shouldReturnConflict() throws Exception {
        Long videoId = 5L;
        Video video = new Video(testUser, "processing.mp4", "Desc", Instant.now(), "path/processing", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setStatus(VideoStatus.PROCESSING); // <-- Already processing

        EditOptions validOptions = new EditOptions(null, null, false, 720);
        String requestBody = objectMapper.writeValueAsString(validOptions);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);

        mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
        verify(videoProcessingService, never()).processVideoEdits(anyLong(), any(EditOptions.class), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void processVideo_whenPreviouslyFailed_shouldReturnConflict() throws Exception {
        Long videoId = 6L;
        Video video = new Video(testUser, "failed.mp4", "Desc", Instant.now(), "path/failed", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setStatus(VideoStatus.FAILED); // <-- Already failed

        EditOptions validOptions = new EditOptions(null, null, false, 720);
        String requestBody = objectMapper.writeValueAsString(validOptions);

        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);

        mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict());

        verify(videoRepository).findById(videoId);
        verify(videoSecurityService).isOwner(videoId, testUser.getUsername());
        verify(videoProcessingService, never()).processVideoEdits(anyLong(), any(EditOptions.class), anyString());
        verify(videoRepository, never()).save(any(Video.class));
    }

    @Test
    void processVideo_whenInvalidEditOptions_shouldReturnBadRequest() throws Exception {
        Long videoId = 7L;
        // Resolution too low based on EditOptions validation annotation
        EditOptions invalidOptions = new EditOptions(null, null, false, 100);
        String requestBody = objectMapper.writeValueAsString(invalidOptions);

        // Mock find/owner check just to ensure validation is reached
        Video video = new Video(testUser, "validate.mp4", "Desc", Instant.now(), "path/validate", 100L, VIDEO_MIME_TYPE);
        video.setId(videoId);
        video.setStatus(VideoStatus.UPLOADED);
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));
        given(videoSecurityService.isOwner(videoId, testUser.getUsername())).willReturn(true);

        mockMvc.perform(addAuth(post("/api/videos/{id}/process", videoId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());

        verify(videoRepository, never()).save(any(Video.class));
        verify(videoProcessingService, never()).processVideoEdits(anyLong(), any(EditOptions.class), anyString());
    }

    // ============ HELPER METHODS ============ //

    private AppUser createTestUser() {
        AppUser user = new AppUser(
                TEST_USERNAME,
                passwordEncoder.encode(TEST_PASSWORD), // Use injected encoder
                "USER",
                TEST_EMAIL
        );
        // Assign a predictable ID for consistency in tests using eq(testUser.getId())
        user.setId(1L);
        return user;
    }

    private byte[] loadSampleVideo() throws Exception {
        // Ensure the test resource exists
        return Files.readAllBytes(Paths.get("src/test/resources/" + SAMPLE_FILENAME));
    }

    /**
     * Configures default success mock behavior for storage service store method
     */
    private void configureDefaultMockBehavior() {
        // Mock the store method - can be overridden per test if needed
        doReturn(DEFAULT_STORAGE_PATH)
                .when(storageService).store(any(MultipartFile.class), anyLong(), anyString());
    }

    private MockMultipartFile createTestVideoFile(String filename) {
        return new MockMultipartFile(
                "file", filename, VIDEO_MIME_TYPE, sampleVideoContent);
    }

    private MockMultipartFile createTestVideoFile() {
        return createTestVideoFile(SAMPLE_FILENAME);
    }
}

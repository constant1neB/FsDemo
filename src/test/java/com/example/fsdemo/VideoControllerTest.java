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
import org.mockito.Mock;
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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
    private static final String UNICODE_FILENAME = "测试视频.mp4";
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String INVALID_MIME_TYPE = "application/pdf";
    private static final String DEFAULT_STORAGE_PATH = "default-storage-path.mp4";
    private static final long MAX_FILE_SIZE_BYTES = 105 * 1024 * 1024; // 105MB
    private static final String LONG_DESCRIPTION = "A".repeat(1024);

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

    @Mock
    private VideoStorageService storageService;

    private String jwtToken;
    private AppUser testUser;
    private byte[] sampleVideoContent;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public VideoStorageService videoStorageService() {
            return Mockito.mock(VideoStorageService.class);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        videoRepository.deleteAll();
        appUserRepository.deleteAll();

        testUser = createTestUser();
        appUserRepository.save(testUser);

        sampleVideoContent = loadSampleVideo();
        configureDefaultMockBehavior();
        jwtToken = authenticateAndGetToken();
    }


    @Test
    void uploadVideo_withUnicodeFilename_shouldSucceed() throws Exception {
        MockMultipartFile unicodeFile = new MockMultipartFile(
                "file",
                UNICODE_FILENAME,
                VIDEO_MIME_TYPE,
                sampleVideoContent
        );

        mockMvc.perform(multipart("/api/videos")
                        .file(unicodeFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isCreated());

        Video savedVideo = videoRepository.findAll().get(0);
        assertThat(savedVideo.getOriginalFilename()).isEqualTo(UNICODE_FILENAME);
        verify(storageService).store(any(), eq(testUser.getId()));
    }

    @Test
    void uploadVideo_withExactMaxSizeFile_shouldSucceed() throws Exception {
        byte[] maxSizeFile = new byte[(int) MAX_FILE_SIZE_BYTES];

        MockMultipartFile largeFile = new MockMultipartFile(
                "file",
                "maxsize.mp4",
                VIDEO_MIME_TYPE,
                maxSizeFile
        );

        mockMvc.perform(multipart("/api/videos")
                        .file(largeFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isCreated());

        assertThat(videoRepository.count()).isEqualTo(1);
        verify(storageService).store(any(), anyLong());
    }

    @Test
    void uploadVideo_withOverMaxSizeFile_shouldFail() throws Exception {
        byte[] oversizedFile = new byte[(int) (MAX_FILE_SIZE_BYTES + 1)];

        MockMultipartFile largeFile = new MockMultipartFile(
                "file",
                "oversize.mp4",
                VIDEO_MIME_TYPE,
                oversizedFile
        );

        mockMvc.perform(multipart("/api/videos")
                        .file(largeFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isPayloadTooLarge());

        assertThat(videoRepository.count()).isZero();
        verify(storageService, never()).store(any(), anyLong());
    }

    @Test
    void uploadVideo_withMaxLengthDescription_shouldSucceed() throws Exception {
        MockMultipartFile videoFile = createTestVideoFile();

        mockMvc.perform(multipart("/api/videos")
                        .file(videoFile)
                        .param("description", LONG_DESCRIPTION)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isCreated());

        Video savedVideo = videoRepository.findAll().get(0);
        assertThat(savedVideo.getDescription()).hasSize(1024);
    }

    @Test
    void uploadVideo_withoutDescription_shouldSucceed() throws Exception {
        MockMultipartFile videoFile = createTestVideoFile();

        mockMvc.perform(multipart("/api/videos")
                        .file(videoFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isCreated());

        Video savedVideo = videoRepository.findAll().get(0);
        assertThat(savedVideo.getDescription()).isNull();
    }

    @Test
    void uploadVideo_withEmptyDescription_shouldSucceed() throws Exception {
        MockMultipartFile videoFile = createTestVideoFile();

        mockMvc.perform(multipart("/api/videos")
                        .file(videoFile)
                        .param("description", "")
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isCreated());

        Video savedVideo = videoRepository.findAll().get(0);
        assertThat(savedVideo.getDescription()).isEmpty();
    }

    @Test
    void uploadVideo_whenUserNotFound_shouldReturnUnauthorized() throws Exception {
        appUserRepository.deleteAll();

        mockMvc.perform(multipart("/api/videos")
                        .file(createTestVideoFile())
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Authentication failed"));
    }

    // ============ CORE TEST CASES ============ //

    @Test
    void uploadVideo_withValidFile_shouldCreateVideo() throws Exception {
        MockMultipartFile videoFile = createTestVideoFile();

        mockMvc.perform(multipart("/api/videos")
                        .file(videoFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.error").doesNotExist());

        List<Video> videos = videoRepository.findAll();
        assertThat(videos).hasSize(1);
        verify(storageService).store(any(), eq(testUser.getId()));
    }

    @Test
    void uploadVideo_withInvalidFileType_shouldReject() throws Exception {
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file",
                "invalid.pdf",
                INVALID_MIME_TYPE,
                "Invalid content".getBytes()
        );

        mockMvc.perform(multipart("/api/videos")
                        .file(invalidFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid file type"));

        assertThat(videoRepository.count()).isZero();
        verify(storageService, never()).store(any(), anyLong());
    }

    @Test
    void uploadVideo_withPathTraversalFilename_shouldReject() throws Exception {
        MockMultipartFile maliciousFile = new MockMultipartFile(
                "file",
                "../malicious.mp4",
                VIDEO_MIME_TYPE,
                sampleVideoContent
        );

        mockMvc.perform(multipart("/api/videos")
                        .file(maliciousFile)
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid filename"));

        assertThat(videoRepository.count()).isZero();
        verify(storageService, never()).store(any(), anyLong());
    }

    @Test
    void uploadVideo_whenStorageFails_shouldReturnServerError() throws Exception {
        doThrow(new VideoStorageException("Storage error"))
                .when(storageService).store(any(), anyLong());

        mockMvc.perform(multipart("/api/videos")
                        .file(createTestVideoFile())
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Video storage failed"));

        assertThat(videoRepository.count()).isZero();
    }

    @Test
    void uploadVideo_withMissingFile_shouldReject() throws Exception {
        mockMvc.perform(multipart("/api/videos")
                        .param("description", "No file")
                        .header(HttpHeaders.AUTHORIZATION, jwtToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("File is required"));
    }

    // ============ HELPER METHODS ============ //

    private AppUser createTestUser() {
        return new AppUser(
                TEST_USERNAME,
                passwordEncoder.encode(TEST_PASSWORD),
                "USER",
                TEST_EMAIL
        );
    }

    private byte[] loadSampleVideo() throws Exception {
        return Files.readAllBytes(Paths.get("src/test/resources/" + SAMPLE_FILENAME));
    }

    private void configureDefaultMockBehavior() {
        when(storageService.store(any(), anyLong())).thenReturn(DEFAULT_STORAGE_PATH);
    }

    private String authenticateAndGetToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/login")
                        .content(String.format("{\"username\":\"%s\", \"password\":\"%s\"}",
                                TEST_USERNAME, TEST_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

        return result.getResponse().getHeader(HttpHeaders.AUTHORIZATION);
    }

    private MockMultipartFile createTestVideoFile() {
        return new MockMultipartFile(
                "file",
                SAMPLE_FILENAME,
                VIDEO_MIME_TYPE,
                sampleVideoContent
        );
    }
}
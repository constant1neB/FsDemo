package com.example.fsdemo.web;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.GlobalExceptionHandler;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.exceptions.VideoValidationException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.security.AuthEntryPoint;
import com.example.fsdemo.security.JwtService;
import com.example.fsdemo.security.SecurityConfig;
import com.example.fsdemo.service.*;
import com.example.fsdemo.web.controller.VideoController;
import com.example.fsdemo.web.dto.EditOptions;
import com.example.fsdemo.web.dto.UpdateVideoRequest;
import com.example.fsdemo.web.dto.VideoDownloadDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VideoController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, AuthEntryPoint.class})
class VideoControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private VideoManagementService videoManagementService;
    @MockitoBean
    private VideoProcessingService videoProcessingService;
    @MockitoBean
    private VideoStatusUpdater videoStatusUpdater;
    @MockitoBean
    private VideoRepository videoRepository;

    @MockitoBean
    private JwtService jwtService;
    @MockitoBean
    private UserDetailsService userDetailsService;


    private static final String TEST_USERNAME = "testUser";
    private static final Long VIDEO_ID = 1L;
    private static final String VIDEO_MIME_TYPE = "video/mp4";
    private static final String MOCK_TOKEN_VALUE = "mock-jwt-token";

    private AppUser testUser;
    private String publicVideoId;
    private Video testVideoEntity;

    private Video createTestVideo(Long id, String publicId, AppUser owner, String description, Video.VideoStatus status, Long fileSize, String mimeType) {
        Video video = new Video(owner, description, Instant.now(), "path/" + publicId + ".mp4", fileSize, mimeType);
        if (id != null) ReflectionTestUtils.setField(video, "id", id);
        ReflectionTestUtils.setField(video, "publicId", publicId);
        video.setStatus(status);
        return video;
    }

    @BeforeEach
    void setUp() {
        testUser = new AppUser(TEST_USERNAME, "password", "USER", "test@example.com");
        testUser.setId(100L);

        publicVideoId = "public-" + UUID.randomUUID();
        testVideoEntity = createTestVideo(VIDEO_ID, publicVideoId, testUser, "Test Video Description",
                Video.VideoStatus.UPLOADED, 1024L, VIDEO_MIME_TYPE);

        lenient().when(videoRepository.findByPublicId(publicVideoId)).thenReturn(Optional.of(testVideoEntity));
    }

    private RequestPostProcessor authenticatedUser() {
        return request -> {
            given(jwtService.validateTokenAndGetUsername(any(HttpServletRequest.class))).willReturn(TEST_USERNAME);
            request.addHeader(HttpHeaders.AUTHORIZATION, JwtService.PREFIX + MOCK_TOKEN_VALUE);
            return request;
        };
    }

    private RequestPostProcessor unauthenticatedUser() {
        return request -> {
            given(jwtService.validateTokenAndGetUsername(any(HttpServletRequest.class))).willReturn(null);
            return request;
        };
    }

    @Test
    @DisplayName("POST /api/videos - Success (with real filter path)")
    void uploadVideo_Success() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile("file", "test.mp4",
                VIDEO_MIME_TYPE, "test content".getBytes());
        String description = "Test video description";
        String newPublicId = "new-public-id-" + UUID.randomUUID();

        Video savedVideo = createTestVideo(2L, newPublicId, testUser, description, Video.VideoStatus.UPLOADED,
                mockFile.getSize(), mockFile.getContentType());

        given(videoManagementService.uploadVideo(any(MultipartFile.class), eq(description), eq(TEST_USERNAME)))
                .willReturn(savedVideo);

        mockMvc.perform(multipart("/api/videos")
                        .file(mockFile)
                        .param("description", description)
                        .with(authenticatedUser())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicId").value(newPublicId))
                .andExpect(jsonPath("$.description").value(description))
                .andExpect(jsonPath("$.status").value(Video.VideoStatus.UPLOADED.toString()));

        verify(videoManagementService).uploadVideo(any(MultipartFile.class), eq(description), eq(TEST_USERNAME));
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos - List user videos (with real filter path)")
    void listUserVideos_ReturnsListOfUserVideos() throws Exception {
        String publicId1 = "uuid-user1";
        String publicId2 = "uuid-user2";
        Video userVideo1 = createTestVideo(1L, publicId1, testUser, "User Video 1",
                Video.VideoStatus.READY, 100L, VIDEO_MIME_TYPE);
        Video userVideo2 = createTestVideo(2L, publicId2, testUser, "User Video 2",
                Video.VideoStatus.PROCESSING, 200L, VIDEO_MIME_TYPE);

        Pageable pageable = PageRequest.of(0, 10);
        Page<Video> videoPage = new PageImpl<>(List.of(userVideo1, userVideo2), pageable, 2);

        given(videoManagementService.listUserVideos(eq(TEST_USERNAME), any(Pageable.class)))
                .willReturn(videoPage);

        mockMvc.perform(get("/api/videos")
                        .param("page", "0")
                        .param("size", "10")
                        .with(authenticatedUser())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].publicId").value(publicId1))
                .andExpect(jsonPath("$.content[1].publicId").value(publicId2))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2));

        verify(videoManagementService).listUserVideos(eq(TEST_USERNAME), any(Pageable.class));
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos - Empty list (with real filter path)")
    void listUserVideos_EmptyList() throws Exception {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Video> emptyPage = new PageImpl<>(Collections.emptyList(), pageable, 0);

        given(videoManagementService.listUserVideos(eq(TEST_USERNAME), any(Pageable.class)))
                .willReturn(emptyPage);

        mockMvc.perform(get("/api/videos")
                        .param("page", "0")
                        .param("size", "10")
                        .with(authenticatedUser())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0));


        verify(videoManagementService).listUserVideos(eq(TEST_USERNAME), any(Pageable.class));
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos/{publicId} - Success (with real filter path)")
    void getVideoDetails_Success() throws Exception {
        given(videoManagementService.getVideoForViewing(VIDEO_ID, TEST_USERNAME)).willReturn(testVideoEntity);

        mockMvc.perform(get("/api/videos/{publicId}", publicVideoId)
                        .with(authenticatedUser())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicVideoId))
                .andExpect(jsonPath("$.description").value(testVideoEntity.getDescription()));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).getVideoForViewing(VIDEO_ID, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("POST /api/videos/{publicId}/process - Success (with real filter path)")
    void processVideo_Success() throws Exception {
        EditOptions editOptions = new EditOptions(5.0, 15.0, false, 480);

        doNothing().when(videoManagementService).authorizeVideoProcessing(VIDEO_ID, TEST_USERNAME);
        doNothing().when(videoStatusUpdater).updateStatusToProcessing(VIDEO_ID);
        doNothing().when(videoProcessingService).processVideoEdits(VIDEO_ID, editOptions, TEST_USERNAME);

        mockMvc.perform(post("/api/videos/{publicId}/process", publicVideoId)
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editOptions)))
                .andExpect(status().isAccepted());

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).authorizeVideoProcessing(VIDEO_ID, TEST_USERNAME);
        verify(videoStatusUpdater).updateStatusToProcessing(VIDEO_ID);
        verify(videoProcessingService).processVideoEdits(VIDEO_ID, editOptions, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos/{publicId}/download - Success (Latest) (with real filter path)")
    void downloadVideo_Success_Latest() throws Exception {
        String downloadFilename = "latest-video.mp4";
        Resource mockResource = new ByteArrayResource("latest video content".getBytes());
        VideoDownloadDetails downloadDetails = new VideoDownloadDetails(mockResource, downloadFilename, VIDEO_MIME_TYPE,
                (long) "latest video content".getBytes().length);

        given(videoManagementService.prepareVideoDownload(VIDEO_ID, TEST_USERNAME)).willReturn(downloadDetails);

        mockMvc.perform(get("/api/videos/{publicId}/download", publicVideoId)
                        .with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloadFilename + "\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, VIDEO_MIME_TYPE))
                .andExpect(content().bytes("latest video content".getBytes()));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).prepareVideoDownload(VIDEO_ID, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos/{publicId}/download/original - Success (with real filter path)")
    void downloadVideo_Success_Original() throws Exception {
        String downloadFilename = "original-video.mp4";
        Resource mockResource = new ByteArrayResource("original video content".getBytes());
        VideoDownloadDetails downloadDetails = new VideoDownloadDetails(mockResource, downloadFilename,
                VIDEO_MIME_TYPE, (long) "original video content".getBytes().length);

        given(videoManagementService.prepareOriginalVideoDownload(VIDEO_ID, TEST_USERNAME)).willReturn(downloadDetails);

        mockMvc.perform(get("/api/videos/{publicId}/download/original", publicVideoId)
                        .with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + downloadFilename + "\""))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, VIDEO_MIME_TYPE))
                .andExpect(content().bytes("original video content".getBytes()));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).prepareOriginalVideoDownload(VIDEO_ID, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("PUT /api/videos/{publicId} - Success (with real filter path)")
    void updateVideoDescription_Success() throws Exception {
        String newDescription = "Updated video description";
        UpdateVideoRequest updateRequest = new UpdateVideoRequest(newDescription);

        Video updatedVideo = createTestVideo(VIDEO_ID, publicVideoId, testUser, newDescription,
                Video.VideoStatus.READY, 1024L, VIDEO_MIME_TYPE);

        given(videoManagementService.updateVideoDescription(VIDEO_ID, newDescription, TEST_USERNAME))
                .willReturn(updatedVideo);

        mockMvc.perform(put("/api/videos/{publicId}", publicVideoId)
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicId").value(publicVideoId))
                .andExpect(jsonPath("$.description").value(newDescription));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).updateVideoDescription(VIDEO_ID, newDescription, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("DELETE /api/videos/{publicId} - Success (with real filter path)")
    void deleteVideo_Success() throws Exception {
        doNothing().when(videoManagementService).deleteVideo(VIDEO_ID, TEST_USERNAME);

        mockMvc.perform(delete("/api/videos/{publicId}", publicVideoId)
                        .with(authenticatedUser()))
                .andExpect(status().isNoContent());

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).deleteVideo(VIDEO_ID, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos/{publicId} - Unauthenticated (401 via AuthEntryPoint)")
    void getVideoDetails_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/videos/{publicId}", publicVideoId)
                        .with(unauthenticatedUser())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title", is("Unauthorized")))
                .andExpect(jsonPath("$.detail", is("Authentication failed or is required to access this resource.")));

        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
        verify(videoRepository, never()).findByPublicId(anyString());
        verify(videoManagementService, never()).getVideoForViewing(anyLong(), anyString());
    }

    @Test
    @DisplayName("GET /api/videos/{publicId} - Video Not Found (404 from getInternalId) (with real filter path)")
    void getVideoDetails_NotFound_FromRepo() throws Exception {
        String nonExistentPublicId = "non-existent-id";
        given(videoRepository.findByPublicId(nonExistentPublicId)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/videos/{publicId}", nonExistentPublicId)
                        .with(authenticatedUser())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title", is("Not Found")))
                .andExpect(jsonPath("$.detail", is("Video not found.")));

        verify(videoRepository).findByPublicId(nonExistentPublicId);
        verify(videoManagementService, never()).getVideoForViewing(anyLong(), anyString());
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos/{publicId} - Video Not Found (404 from service) (with real filter path)")
    void getVideoDetails_NotFound_FromService() throws Exception {
        given(videoManagementService.getVideoForViewing(VIDEO_ID, TEST_USERNAME))
                .willThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found by service"));

        mockMvc.perform(get("/api/videos/{publicId}", publicVideoId)
                        .with(authenticatedUser())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title", is("Not Found")))
                .andExpect(jsonPath("$.detail", is("Video not found by service")));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).getVideoForViewing(VIDEO_ID, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("GET /api/videos/{publicId} - Forbidden (403) (with real filter path)")
    void getVideoDetails_Forbidden() throws Exception {
        given(videoManagementService.getVideoForViewing(VIDEO_ID, TEST_USERNAME))
                .willThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "User does not have permission"));

        mockMvc.perform(get("/api/videos/{publicId}", publicVideoId)
                        .with(authenticatedUser())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title", is("Forbidden")))
                .andExpect(jsonPath("$.detail", is("User does not have permission")));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).getVideoForViewing(VIDEO_ID, TEST_USERNAME);
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("POST /api/videos/{publicId}/process - Status Conflict (409 from service) (with real filter path)")
    void processVideo_StatusConflict() throws Exception {
        EditOptions editOptions = new EditOptions(5.0, 15.0, false, 480);
        doNothing().when(videoManagementService).authorizeVideoProcessing(VIDEO_ID, TEST_USERNAME);
        doThrow(new IllegalStateException("Video cannot be processed in its current state: PROCESSING"))
                .when(videoStatusUpdater).updateStatusToProcessing(VIDEO_ID);


        mockMvc.perform(post("/api/videos/{publicId}/process", publicVideoId)
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editOptions))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title", is("Conflict")))
                .andExpect(jsonPath("$.detail", is("Video cannot be processed in its current state: PROCESSING")));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).authorizeVideoProcessing(VIDEO_ID, TEST_USERNAME);
        verify(videoStatusUpdater).updateStatusToProcessing(VIDEO_ID);
        verify(videoProcessingService, never()).processVideoEdits(anyLong(), any(EditOptions.class), anyString());
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("POST /api/videos - VideoValidationException (400) (with real filter path)")
    void uploadVideo_ValidationException() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file", "test.mp4", VIDEO_MIME_TYPE, "test content".getBytes());
        String description = "Test video description";

        given(videoManagementService.uploadVideo(any(MultipartFile.class), eq(description), eq(TEST_USERNAME)))
                .willThrow(new VideoValidationException(HttpStatus.BAD_REQUEST, "Invalid file type"));

        mockMvc.perform(multipart("/api/videos")
                        .file(mockFile)
                        .param("description", description)
                        .with(authenticatedUser())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Bad Request")))
                .andExpect(jsonPath("$.detail", is("Invalid file type")));
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("POST /api/videos - VideoStorageException (500) (with real filter path)")
    void uploadVideo_StorageException() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile("file", "test.mp4",
                VIDEO_MIME_TYPE, "test content".getBytes());
        String description = "Test video description";

        given(videoManagementService.uploadVideo(any(MultipartFile.class), eq(description), eq(TEST_USERNAME)))
                .willThrow(new VideoStorageException("Failed to store file"));

        mockMvc.perform(multipart("/api/videos")
                        .file(mockFile)
                        .param("description", description)
                        .with(authenticatedUser())
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title", is("Internal Server Error")))
                .andExpect(jsonPath("$.detail", is(
                        "Failed to process video storage operation. Please contact support if the problem persists.")));
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }

    @Test
    @DisplayName("POST /api/videos/{publicId}/process - Generic Exception (500 from GlobalExceptionHandler) (with real filter path)")
    void processVideo_GenericException() throws Exception {
        EditOptions editOptions = new EditOptions(5.0, 15.0, false, 480);

        doNothing().when(videoManagementService).authorizeVideoProcessing(VIDEO_ID, TEST_USERNAME);
        doThrow(new RuntimeException("Simulated internal error during status update"))
                .when(videoStatusUpdater).updateStatusToProcessing(VIDEO_ID);


        mockMvc.perform(post("/api/videos/{publicId}/process", publicVideoId)
                        .with(authenticatedUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(editOptions))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title", is("Internal Server Error")))
                .andExpect(jsonPath("$.detail", is(
                        "An unexpected internal error occurred. Please try again later or contact support.")));

        verify(videoRepository).findByPublicId(publicVideoId);
        verify(videoManagementService).authorizeVideoProcessing(VIDEO_ID, TEST_USERNAME);
        verify(videoStatusUpdater).updateStatusToProcessing(VIDEO_ID);
        verify(videoProcessingService, never()).processVideoEdits(anyLong(), any(EditOptions.class), anyString());
        verify(jwtService).validateTokenAndGetUsername(any(HttpServletRequest.class));
    }
}
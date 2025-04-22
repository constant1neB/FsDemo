package com.example.fsdemo.web.controller;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.service.*;
import com.example.fsdemo.web.dto.EditOptions;
import com.example.fsdemo.web.dto.UpdateVideoRequest;
import com.example.fsdemo.web.dto.VideoDownloadDetails;
import com.example.fsdemo.web.dto.VideoResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);

    private final VideoManagementService videoManagementService;
    private final VideoProcessingService videoProcessingService;
    private final VideoStatusUpdater videoStatusUpdater;


    public VideoController(
            VideoManagementService videoManagementService,
            VideoProcessingService videoProcessingService,
            VideoStatusUpdater videoStatusUpdater) {

        this.videoManagementService = videoManagementService;
        this.videoProcessingService = videoProcessingService;
        this.videoStatusUpdater = videoStatusUpdater;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoResponse> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            Authentication authentication) {

        String username = authentication.getName();
        log.info("Upload request received in controller from user: {}", username);
        Video savedVideo = videoManagementService.uploadVideo(file, description, username);
        VideoResponse responseDto = VideoResponse.fromEntity(savedVideo);
        log.info("Controller returning CREATED for video ID: {}", savedVideo.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }

    @GetMapping
    public ResponseEntity<List<VideoResponse>> listUserVideos(Authentication authentication) {
        String username = authentication.getName();
        log.debug("List videos request received for user: {}", username);
        List<Video> userVideos = videoManagementService.listUserVideos(username);
        List<VideoResponse> responseDtos = userVideos.stream()
                .map(VideoResponse::fromEntity)
                .toList();
        log.info("Returning {} videos for user: {}", responseDtos.size(), username);
        return ResponseEntity.ok(responseDtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VideoResponse> getVideoDetails(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        log.debug("Get video details request received for ID: {} from user: {}", id, username);
        Video video = videoManagementService.getVideoForViewing(id, username);
        VideoResponse responseDto = VideoResponse.fromEntity(video);
        log.info("Returning details for video ID: {}", id);
        return ResponseEntity.ok(responseDto);
    }

    @PostMapping(value = "/{id}/process", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> processVideo(
            @PathVariable Long id,
            @RequestBody @Valid EditOptions options,
            Authentication authentication) {

        String username = authentication.getName();
        log.info("Processing request received for video ID: {} from user: {}", id, username);

        // Authorize first using the service method
        videoManagementService.authorizeVideoProcessing(id, username);

        // Proceed with status update and processing trigger
        try {
            videoStatusUpdater.updateStatusToProcessing(id);
            log.debug("Status update to PROCESSING requested successfully for video ID: {}", id);
            videoProcessingService.processVideoEdits(id, options, username);
        } catch (IllegalStateException e) {
            log.warn("Processing conflict for user: {} on video ID: {}. Reason: {}", username, id, e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("current state")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
            } else if (e.getMessage() != null && e.getMessage().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
            } else {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to initiate video processing.", e);
            }
        } // Other exceptions handled globally

        log.info("Returning 202 Accepted for processing request of video ID: {}", id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadVideo(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        log.debug("Download request received in controller for video ID: {} from user: {}", id, username);
        VideoDownloadDetails downloadDetails = videoManagementService.prepareVideoDownload(id, username);
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(downloadDetails.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadDetails.downloadFilename() + "\"");
        if (downloadDetails.contentLength() != null) {
            responseBuilder.contentLength(downloadDetails.contentLength());
            log.debug("Successfully set content length ({}) from DTO for video ID: {}", downloadDetails.contentLength(), id);
        } else {
            log.warn("Content length was null in DownloadDetails for video ID: {}. Proceeding without.", id);
        }
        return responseBuilder.body(downloadDetails.resource());
    }

    @PutMapping("/{id}")
    public ResponseEntity<VideoResponse> updateVideoDescription(
            @PathVariable Long id,
            @RequestBody @Valid UpdateVideoRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        log.debug("Update video description request received for ID: {} from user: {}", id, username);
        Video savedVideo = videoManagementService.updateVideoDescription(id, request.description(), username);
        VideoResponse responseDto = VideoResponse.fromEntity(savedVideo);
        log.info("Controller returning OK for updated video ID: {}", savedVideo.getId());
        return ResponseEntity.ok(responseDto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteVideo(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        log.debug("Delete request received in controller for video ID: {} from user: {}", id, username);
        videoManagementService.deleteVideo(id, username);
        log.info("Controller returning NoContent for delete request of video ID: {}", id);
        return ResponseEntity.noContent().build();
    }
}
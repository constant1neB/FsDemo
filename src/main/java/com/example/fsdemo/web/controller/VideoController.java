// FILE: src/main/java/com/example/fsdemo/web/controller/VideoController.java
package com.example.fsdemo.web.controller;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.repository.VideoRepository;
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
    private static final String VIDEO_NOT_FOUND_MSG = "Video not found.";

    private final VideoManagementService videoManagementService;
    private final VideoProcessingService videoProcessingService;
    private final VideoStatusUpdater videoStatusUpdater;
    private final VideoRepository videoRepository;


    public VideoController(
            VideoManagementService videoManagementService,
            VideoProcessingService videoProcessingService,
            VideoStatusUpdater videoStatusUpdater,
            VideoRepository videoRepository) {

        this.videoManagementService = videoManagementService;
        this.videoProcessingService = videoProcessingService;
        this.videoStatusUpdater = videoStatusUpdater;
        this.videoRepository = videoRepository;
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

    @GetMapping("/{publicId}")
    public ResponseEntity<VideoResponse> getVideoDetails(@PathVariable String publicId, Authentication authentication) {
        String username = authentication.getName();
        Long id = getInternalId(publicId);
        Video video = videoManagementService.getVideoForViewing(id, username);
        VideoResponse responseDto = VideoResponse.fromEntity(video);
        return ResponseEntity.ok(responseDto);
    }

    @PostMapping(value = "/{publicId}/process", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> processVideo(
            @PathVariable String publicId,
            @RequestBody @Valid EditOptions options,
            Authentication authentication) {

        String username = authentication.getName();
        Long id = getInternalId(publicId);

        // Authorize first using the service method
        videoManagementService.authorizeVideoProcessing(id, username);

        // Proceed with status update and processing trigger
        try {
            videoStatusUpdater.updateStatusToProcessing(id);
            log.debug("Status update to PROCESSING requested successfully for video ID: {}", id);
            videoProcessingService.processVideoEdits(id, options, username);
        } catch (IllegalStateException e) {
            log.warn("Processing conflict for user: {} on video ID: {}. Reason: {}", username, id, e.getMessage());
            if (e.getMessage() != null && (e.getMessage().contains("current state") || e.getMessage().contains("cannot transition"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
            } else if (e.getMessage() != null && e.getMessage().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
            } else {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to initiate video processing.", e);
            }
        }

        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{publicId}/download")
    public ResponseEntity<Resource> downloadVideo(@PathVariable String publicId, Authentication authentication) {
        String username = authentication.getName();
        Long id = getInternalId(publicId);
        VideoDownloadDetails downloadDetails = videoManagementService.prepareVideoDownload(id, username); // Uses existing service method

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(downloadDetails.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadDetails.downloadFilename() + "\"");
        if (downloadDetails.contentLength() != null) {
            responseBuilder.contentLength(downloadDetails.contentLength());
            log.debug("Successfully set content length ({}) for LATEST download from DTO for video ID: {}", downloadDetails.contentLength(), id);
        } else {
            log.warn("Content length was null in DownloadDetails for LATEST download for video ID: {}. Proceeding without.", id);
        }
        return responseBuilder.body(downloadDetails.resource());
    }

    @GetMapping("/{publicId}/download/original")
    public ResponseEntity<Resource> downloadOriginalVideo(@PathVariable String publicId, Authentication authentication) {
        String username = authentication.getName();
        Long id = getInternalId(publicId);
        VideoDownloadDetails downloadDetails = videoManagementService.prepareOriginalVideoDownload(id, username);

        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(downloadDetails.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadDetails.downloadFilename() + "\"");

        if (downloadDetails.contentLength() != null) {
            responseBuilder.contentLength(downloadDetails.contentLength());
            log.debug("Successfully set content length ({}) for ORIGINAL download from DTO for video ID: {}", downloadDetails.contentLength(), id);
        } else {
            log.warn("Content length was null in DownloadDetails for ORIGINAL download for video ID: {}. Proceeding without.", id);
        }
        return responseBuilder.body(downloadDetails.resource());
    }


    @PutMapping("/{publicId}")
    public ResponseEntity<VideoResponse> updateVideoDescription(
            @PathVariable String publicId,
            @RequestBody @Valid UpdateVideoRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        Long id = getInternalId(publicId);
        Video savedVideo = videoManagementService.updateVideoDescription(id, request.description(), username);
        VideoResponse responseDto = VideoResponse.fromEntity(savedVideo);
        return ResponseEntity.ok(responseDto);
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<Void> deleteVideo(@PathVariable String publicId, Authentication authentication) {
        String username = authentication.getName();
        Long id = getInternalId(publicId);
        videoManagementService.deleteVideo(id, username);
        return ResponseEntity.noContent().build();
    }

    /**
     * Finds the internal ID of a video by its public ID.
     *
     * @param publicId The public ID of the video to look up
     * @return The internal Long ID of the video
     * @throws ResponseStatusException with HTTP status 404 (Not Found) if no video with the given public ID exists
     */
    private Long getInternalId(String publicId) {
        return videoRepository.findByPublicId(publicId)
                .map(Video::getId)
                .orElseThrow(() -> {
                    log.warn("Video lookup failed for public ID: {}", publicId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MSG);
                });
    }
}
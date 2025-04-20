package com.example.fsdemo.web.controller;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.exceptions.VideoValidationException;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.*;
import com.example.fsdemo.web.dto.EditOptions;
import com.example.fsdemo.web.dto.UpdateVideoRequest;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);

    private final VideoStorageService storageService;
    private final VideoRepository videoRepository;
    private final AppUserRepository appUserRepository;
    private final VideoSecurityService videoSecurityService;
    private final VideoProcessingService videoProcessingService;
    private final VideoUploadValidator videoUploadValidator;

    private static final EnumSet<Video.VideoStatus> ALLOWED_START_PROCESSING_STATUSES =
            EnumSet.of(Video.VideoStatus.UPLOADED, Video.VideoStatus.READY);

    public static final String VIDEO_NOT_FOUND_MESSAGE = "Video not found";

    private static final String MP4_EXTENSION = ".mp4";


    public VideoController(VideoStorageService storageService,
                           VideoRepository videoRepository,
                           AppUserRepository appUserRepository,
                           VideoSecurityService videoSecurityService,
                           VideoProcessingService videoProcessingService,
                           VideoUploadValidator videoUploadValidator) {
        this.storageService = storageService;
        this.videoRepository = videoRepository;
        this.appUserRepository = appUserRepository;
        this.videoSecurityService = videoSecurityService;
        this.videoProcessingService = videoProcessingService;
        this.videoUploadValidator = videoUploadValidator;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional(rollbackFor = Exception.class)
    public ResponseEntity<VideoResponse> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            Authentication authentication) {

        // 1. Get Authenticated User
        String username = authentication.getName();
        AppUser owner = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        log.info("Video upload request received from user: {}", username);

        // 2. Delegate Validation
        try {
            videoUploadValidator.validate(file);
        } catch (VideoValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error during file validation", e);
        }
        // Validation Passed

        // 3. Generate Secure Filename (UUID)
        String generatedFilename = UUID.randomUUID() + MP4_EXTENSION;
        log.info("Validation passed. Generated secure filename {} for file uploaded by user {}", generatedFilename, username);

        // 4. Store the file using the GENERATED filename
        log.debug("Calling storage service to store file {} for user {}", generatedFilename, username);
        String storagePath;
        try {
            storagePath = storageService.store(file, owner.getId(), generatedFilename);
        } catch (VideoStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store video due to an unexpected error.", e);
        }


        // 5. Create Video Entity
        Video video = new Video(
                owner,
                generatedFilename,
                description,
                Instant.now(),
                storagePath,
                file.getSize(),
                file.getContentType()
        );
        video.setStatus(Video.VideoStatus.UPLOADED);

        log.debug("Attempting to save video metadata: Owner={}, GeneratedFilename={}, Size={}, MimeType={}",
                video.getOwner().getUsername(),
                video.getGeneratedFilename(),
                video.getFileSize(),
                video.getMimeType());

        // 6. Save metadata to database
        Video savedVideo = videoRepository.save(video);
        log.info("Successfully saved video metadata for ID: {}", savedVideo.getId());

        // 7. Create Response DTO
        VideoResponse responseDto = new VideoResponse(
                savedVideo.getId(),
                savedVideo.getDescription(),
                savedVideo.getOwner().getUsername(),
                savedVideo.getFileSize()
        );

        // 8. Return "Created" response
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }

    @GetMapping
    public ResponseEntity<List<VideoResponse>> listUserVideos(Authentication authentication) {
        String username = authentication.getName();
        log.debug("List videos request received for user: {}", username);

        // 1. Fetch videos for the authenticated user from the repository
        List<Video> userVideos = videoRepository.findByOwnerUsername(username);

        // 2. Map Video entities to VideoResponse DTOs
        List<VideoResponse> responseDtos = userVideos.stream()
                .map(video -> new VideoResponse(
                        video.getId(),
                        video.getDescription(),
                        video.getOwner().getUsername(),
                        video.getFileSize()
                ))
                .toList();

        log.info("Returning {} videos for user: {}", responseDtos.size(), username);

        // 3. Return the list in the response body
        return ResponseEntity.ok(responseDtos);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VideoResponse> getVideoDetails(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        log.debug("Get video details request received for ID: {} from user: {}", id, username);

        // 1. Find Video by ID
        Video video = videoRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Get details failed: Video not found for ID: {}", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MESSAGE);
                });

        // 2. Check Permissions using VideoSecurityService
        if (!videoSecurityService.canView(id, username)) {
            log.warn("Get details forbidden for user: {} on video ID: {}", username, id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to access this video's details");
        }

        // 3. Map to DTO
        VideoResponse responseDto = new VideoResponse(
                video.getId(),
                video.getDescription(),
                video.getOwner().getUsername(),
                video.getFileSize()
        );

        log.info("Returning details for video ID: {}", id);
        return ResponseEntity.ok(responseDto);
    }

    @PostMapping(value = "/{id}/process", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<Void> processVideo(
            @PathVariable Long id,
            @RequestBody @Valid EditOptions options,
            Authentication authentication) {

        String username = authentication.getName();
        log.info("Processing request received for video ID: {} from user: {}", id, username);

        // 1. Find Video by ID
        Video video = videoRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Processing failed: Video not found for ID: {}", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MESSAGE);
                });

        // 2. Check Ownership
        if (!videoSecurityService.isOwner(id, username)) {
            log.warn("Processing forbidden for user: {} on video ID: {}", username, id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to process this video");
        }

        // 3. Check Current Status
        if (!ALLOWED_START_PROCESSING_STATUSES.contains(video.getStatus())) {
            log.warn("Processing conflict for user: {} on video ID: {}. Current status is '{}', requires UPLOADED or READY.",
                    username, id, video.getStatus());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Video cannot be processed in its current state: " + video.getStatus());
        }

        // 4. Update Status and Clear Previous Processed Path
        log.debug("Updating status to PROCESSING for video ID: {}", id);
        video.setStatus(Video.VideoStatus.PROCESSING);
        video.setProcessedStoragePath(null); // Clear any old path before starting new process

        // 5. Save Status Update
        videoRepository.save(video);
        log.debug("Status updated and saved for video ID: {}", id);

        // 6. Trigger Asynchronous Processing
        log.info("Calling async processing service for video ID: {}", id);
        videoProcessingService.processVideoEdits(id, options, username);

        // 7. Return Accepted (202) immediately
        log.info("Returning 202 Accepted for processing request of video ID: {}", id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadVideo(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        log.debug("Download request received for video ID: {} from user: {}", id, username);

        // 1. Find Video by ID
        Video video = videoRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Download failed: Video not found for ID: {}", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MESSAGE);
                });

        // 2. Check Permissions
        if (!videoSecurityService.canView(id, username)) {
            log.warn("Download forbidden for user: {} on video ID: {}", username, id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to access this video");
        }

        // 3. Determine which file to download: processed if READY, original otherwise
        String pathTodownload;
        if (video.getStatus() == Video.VideoStatus.READY && video.getProcessedStoragePath() != null && !video.getProcessedStoragePath().isBlank()) {
            pathTodownload = video.getProcessedStoragePath();
            log.debug("Preparing download for processed video. ID: {}, Path: {}", id, pathTodownload);
        } else {
            pathTodownload = video.getStoragePath(); // Original path
            log.debug("Preparing download for original video. ID: {}, Status: {}, Path: {}", id, video.getStatus(), pathTodownload);
        }

        if (pathTodownload == null || pathTodownload.isBlank()) {
            log.error("Download failed for video ID {}: Storage path is missing or blank. Status: {}", id, video.getStatus());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video file path is missing");
        }

        // 4. Load Resource from Storage using the determined path
        log.debug("Loading video resource for ID: {} from path: {}", id, pathTodownload);
        Resource resource;
        try {
            resource = storageService.load(pathTodownload);
        } catch (VideoStorageException e) {
            // Distinguish between file not found (could be 404 or 500 depending on cause) vs other storage errors
            if (e.getMessage() != null && e.getMessage().contains("Could not read file")) {
                // This implies the file expected based on DB record doesn't exist in storage
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Video file not found in storage", e);
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving video file", e);
        }

        if (!resource.exists() || !resource.isReadable()) {
            log.error("Download failed for video ID {}: Resource loaded but not found or not readable at path: {}", id, pathTodownload);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving video file [exists/readable check failed]");
        }

        // 5. Generate New UUID Filename for Download (to hide internal structure)
        String downloadFilename = UUID.randomUUID() + MP4_EXTENSION;
        log.info("Generated download filename {} for video ID: {}", downloadFilename, id);

        // 6. Build Response
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(video.getMimeType())) // Use MIME type from DB
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadFilename + "\"");
        try {
            responseBuilder.contentLength(resource.contentLength());
            log.debug("Successfully determined content length ({}) for video ID: {}", resource.contentLength(), id);
        } catch (IOException e) {
            log.warn("Could not determine content length for video ID: {}. Proceeding without Content-Length header. Error: {}",
                    id, e.getMessage());
        }
        return responseBuilder.body(resource);
    }

    @PutMapping("/{id}")
    @Transactional // Ensure DB operation is atomic
    public ResponseEntity<VideoResponse> updateVideoDescription(
            @PathVariable Long id,
            @RequestBody @Valid UpdateVideoRequest request,
            Authentication authentication) {

        String username = authentication.getName();
        log.debug("Update video description request received for ID: {} from user: {}", id, username);

        // 1. Find Video by ID
        Video video = videoRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Update failed: Video not found for ID: {}", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MESSAGE);
                });

        // 2. Check Permissions (Ownership required for update)
        if (!videoSecurityService.isOwner(id, username)) {
            log.warn("Update forbidden for user: {} on video ID: {}", username, id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to update this video");
        }

        // 3. Update Description
        String newDescription = request.description() != null ? request.description() : "";
        video.setDescription(newDescription);
        log.trace("Updating description for video ID: {}", id);

        // 4. Save Updated Video (within transaction)
        Video savedVideo = videoRepository.save(video);
        log.info("Successfully updated description for video ID: {}", savedVideo.getId());

        // 5. Map to DTO and Return
        VideoResponse responseDto = new VideoResponse(
                savedVideo.getId(),
                savedVideo.getDescription(),
                savedVideo.getOwner().getUsername(),
                savedVideo.getFileSize()
        );

        return ResponseEntity.ok(responseDto);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> deleteVideo(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        log.debug("Delete video request received for ID: {} from user: {}", id, username);

        // 1. Find Video by ID
        Video video = videoRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Delete failed: Video not found for ID: {}", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MESSAGE);
                });

        // 2. Check Permissions
        if (!videoSecurityService.canDelete(id, username)) {
            log.warn("Delete forbidden for user: {} on video ID: {}", username, id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to delete this video");
        }

        // 3. Get Storage Paths BEFORE deleting DB record
        String originalStoragePath = video.getStoragePath();
        String processedStoragePath = video.getProcessedStoragePath();

        // 4. Delete from Database FIRST
        log.debug("Attempting to delete video metadata from database for ID: {}", id);
        try {
            videoRepository.delete(video);
            log.info("Successfully deleted video metadata from database for ID: {}", id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete video record", e);
        }

        // 5. Delete Files from Storage (Outside DB Transaction)
        deleteFileFromStorage(originalStoragePath, id, "original");
        deleteFileFromStorage(processedStoragePath, id, "processed");

        // 6. Return No Content
        log.info("Successfully processed delete request for video ID: {}", id);
        return ResponseEntity.noContent().build();
    }

    // Helper method for file deletion and logging
    private void deleteFileFromStorage(String storagePath, Long videoId, String fileType) {
        if (storagePath == null || storagePath.isBlank()) {
            log.debug("No {} storage path found for video ID {}, skipping file deletion.", fileType, videoId);
            return;
        }

        log.debug("Attempting to delete {} video file for ID: {}", fileType, videoId);
        try {
            storageService.delete(storagePath);
            log.info("Successfully deleted {} video file for ID: {}", fileType, videoId);
        } catch (VideoStorageException e) {
            // Log as warning: DB record is gone, but file might linger. Needs monitoring/cleanup.
            log.warn("Failed to delete {} file from storage after DB record deletion. Orphaned file likely exists. VideoID: {}, Reason: {}", fileType, videoId, e.getMessage(), e);
        } catch (Exception e) {
            // Catch unexpected errors during deletion
            log.warn("Unexpected error deleting {} file from storage after DB record deletion. Orphaned file likely exists. VideoID: {}, Reason: {}", fileType, videoId, e.getMessage(), e);
        }
    }
}
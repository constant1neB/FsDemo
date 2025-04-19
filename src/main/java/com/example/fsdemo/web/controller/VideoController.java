package com.example.fsdemo.web.controller;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.VideoProcessingService;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.service.VideoSecurityService;
import com.example.fsdemo.web.dto.EditOptions;
import com.example.fsdemo.web.dto.UpdateVideoRequest;
import com.example.fsdemo.web.dto.VideoResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;
import java.util.List;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);

    private final VideoStorageService storageService;
    private final VideoRepository videoRepository;
    private final AppUserRepository appUserRepository;
    private final VideoSecurityService videoSecurityService;
    private final VideoProcessingService videoProcessingService;


    @Value("${video.upload.max-size-mb:40}")
    private long maxFileSizeMb;

    private static final EnumSet<Video.VideoStatus> ALLOWED_START_PROCESSING_STATUSES =
            EnumSet.of(Video.VideoStatus.UPLOADED, Video.VideoStatus.READY);

    private static final String ALLOWED_EXTENSION = ".mp4";
    private static final String ALLOWED_CONTENT_TYPE = "video/mp4";

    private static final byte[] MP4_MAGIC_BYTES_FTYP = new byte[]{0x66, 0x74, 0x79, 0x70};
    private static final int MAGIC_BYTE_OFFSET = 4;
    private static final int MAGIC_BYTE_READ_LENGTH = 8;

    public static final String VIDEO_NOT_FOUND_MESSAGE = "Video not found";

    public VideoController(VideoStorageService storageService,
                           VideoRepository videoRepository,
                           AppUserRepository appUserRepository,
                           VideoSecurityService videoSecurityService,
                           VideoProcessingService videoProcessingService) {
        this.storageService = storageService;
        this.videoRepository = videoRepository;
        this.appUserRepository = appUserRepository;
        this.videoSecurityService = videoSecurityService;
        this.videoProcessingService = videoProcessingService;
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

        // 2. Initial File Checks (BEFORE generating UUID or calling storage)
        if (file.isEmpty()) {
            log.warn("Upload rejected for user {}: File is empty.", username);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File cannot be empty");
        }

        // Size Check (using configured value)
        long maxSizeBytes = maxFileSizeMb * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            log.warn("Upload rejected for user {}: File size {} exceeds limit of {}MB.", username, file.getSize(), maxFileSizeMb);
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File size exceeds maximum limit of " + maxFileSizeMb + "MB");
        }

        // Original Filename Validation (for sanity check ONLY, not for storage)
        String originalFilenameRaw = file.getOriginalFilename();
        if (originalFilenameRaw == null || originalFilenameRaw.isBlank()) {
            // While we don't use it, a blank original name might indicate issues.
            log.warn("Upload rejected for user {}: Original file name is missing or blank.", username);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Original file name is missing or blank.");
        }
        // Check for control characters or path traversal attempts in the original name
        if (originalFilenameRaw.matches(".*\\p{Cntrl}.*") || originalFilenameRaw.contains("..") || originalFilenameRaw.contains("/") || originalFilenameRaw.contains("\\")) {
            log.warn("Upload rejected for user {}: Invalid characters detected in original filename.", username);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid characters in original filename.");
        }
        // Clean the path just in case
        String sanitizedOriginalFilename = StringUtils.cleanPath(originalFilenameRaw);


        // Extension Check (on sanitized original filename)
        if (!sanitizedOriginalFilename.toLowerCase().endsWith(ALLOWED_EXTENSION)) {
            log.warn("Upload rejected for user {}: Invalid file extension. Expected '{}'.", username, ALLOWED_EXTENSION);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file type. Only " + ALLOWED_EXTENSION + " files are allowed.");
        }

        // Content-Type Check
        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            log.warn("Upload rejected for user {}: Invalid content type '{}'. Expected '{}'.", username, contentType, ALLOWED_CONTENT_TYPE);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid content type detected. Expected " + ALLOWED_CONTENT_TYPE + ".");
        }

        // Magic Byte Check
        try {
            if (!hasMp4MagicBytes(file)) {
                log.warn("Upload rejected for user {}: File failed magic byte validation. (ContentType: {})", username, contentType);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File content does not appear to be a valid MP4 video.");
            }
            log.debug("Magic byte validation passed for file from user {}", username);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading file for validation.", e);
        }
        // *** Add Antivirus Scan Hook here if integrating ***

        // 3. Generate Secure Filename (UUID) - after validation passes
        String generatedFilename = UUID.randomUUID() + ALLOWED_EXTENSION;
        log.info("Validation passed. Generated secure filename for original file uploaded by user {}", username);

        // 4. Store the file using the GENERATED filename
        log.debug("Calling storage service to store file for user {}", username);
        String storagePath = storageService.store(file, owner.getId(), generatedFilename);
        log.debug("File stored successfully for user {}", username);
        // Let VideoStorageException or other RuntimeExceptions propagate

        // 5. Create Video Entity (Using generated filename, not original)
        Video video = new Video(
                owner,
                generatedFilename, // Use the generated UUID-based filename
                description,       // User-provided description
                Instant.now(),     // Upload timestamp
                storagePath,       // Use the path returned by the storage service
                file.getSize(),    // Store file size
                contentType        // Store the validated content type
        );
        video.setStatus(Video.VideoStatus.UPLOADED); // Set initial status

        log.debug("Attempting to save video metadata: Owner={}, Desc={}, Size={}, MimeType={}",
                video.getOwner().getUsername(),
                video.getDescription(),
                video.getFileSize(),
                video.getMimeType());

        // 6. Save metadata to database
        Video savedVideo = videoRepository.save(video);
        log.info("Successfully saved video metadata for ID: {}", savedVideo.getId());

        // 7. Create Response DTO (using the generated filename)
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
            // Return 403 Forbidden if user cannot view
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
    @Transactional // Ensure status update is atomic with checks
    public ResponseEntity<Void> processVideo(
            @PathVariable Long id,
            @RequestBody @Valid EditOptions options, // Receive JSON body with options
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
            // Use 409 Conflict if already processing or failed
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Video cannot be processed in its current state: " + video.getStatus());
        }
        // NOTE: @Valid on EditOptions handles input validation based on annotations in EditOptions record

        // 4. Update Status and Clear Previous Processed Path
        log.debug("Updating status to PROCESSING for video ID: {}", id);
        video.setStatus(Video.VideoStatus.PROCESSING);
        video.setProcessedStoragePath(null); // Clear any old processed path before starting new process

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
            // Return 403 Forbidden if user cannot view (more specific than 404)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to access this video");
        }

        // 3. Load Resource from Storage
        log.debug("Loading video resource for ID: {}", id);
        Resource resource = storageService.load(video.getStoragePath());
        // Let VideoStorageException propagate

        // Check if resource exists and is readable (basic check)
        if (!resource.exists() || !resource.isReadable()) {
            log.error("Download failed: Resource not found or not readable for video ID: {}", id);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error retrieving video file [exists/readable check failed]");
        }

        // 4. Generate New UUID Filename for Download
        // We don't want to expose the internal storage filename or the stored UUID.
        String downloadFilename = UUID.randomUUID() + ALLOWED_EXTENSION;
        log.info("Generated download filename for video ID: {}", id);

        // 5. Build Response
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(video.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadFilename + "\"");
        try {
            // Attempt to set Content-Length
            responseBuilder.contentLength(resource.contentLength());
            log.debug("Successfully determined content length ({}) for video ID: {}", resource.contentLength(), id);
        } catch (IOException e) {
            // Log the error but proceed without the Content-Length header
            log.warn("Could not determine content length for video ID: {}. Proceeding without Content-Length header. Error: {}",
                    id, e.getMessage());
            // No need to re-throw or set error status here, just omit the header
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
        // Handle null description in request gracefully (e.g., set to empty string or keep existing)
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
    @Transactional // Ensure atomicity (as much as possible with external storage)
    public ResponseEntity<Void> deleteVideo(@PathVariable Long id, Authentication authentication) {
        String username = authentication.getName();
        log.debug("Delete video request received for ID: {} from user: {}", id, username);

        // 1. Find Video by ID
        Video video = videoRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Delete failed: Video not found for ID: {}", id);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MESSAGE);
                });

        // 2. Check Permissions (Deletion requires specific permission)
        if (!videoSecurityService.canDelete(id, username)) {
            log.warn("Delete forbidden for user: {} on video ID: {}", username, id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to delete this video");
        }

        // 3. Delete from Storage Service FIRST
        String storagePath = video.getStoragePath();
        if (storagePath == null || storagePath.isBlank()) {
            // This case shouldn't happen with current logic, but good to handle defensively
            log.error("Delete failed: Video ID {} has missing or blank storage path.", id);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video metadata is inconsistent (missing storage path)");
        }

        log.trace("Attempting to delete video file for ID: {}", id);
        try {
            storageService.delete(storagePath); // Actually call the delete method
            log.info("Successfully deleted video file for ID: {}", id);
        } catch (VideoStorageException e) {
            throw e;
        } catch (Exception e) {
            throw new VideoStorageException("Unexpected error during file deletion for video " + id, e);
        }

        // 4. Delete from Database (only if storage deletion succeeded)
        videoRepository.delete(video);
        log.info("Successfully deleted video metadata from database for ID: {}", id);

        // 5. Return No Content
        return ResponseEntity.noContent().build(); // Standard for successful DELETE
    }

    /**
     * Checks if the provided MultipartFile starts with the expected MP4 magic bytes.
     * Reads only the necessary starting bytes.
     *
     * @param file The MultipartFile to check.
     * @return true if the magic bytes match, false otherwise.
     * @throws IOException If an error occurs reading the file stream.
     */
    private boolean hasMp4MagicBytes(MultipartFile file) throws IOException {
        // Use try-with-resources to ensure the InputStream is closed
        try (InputStream inputStream = file.getInputStream()) {
            byte[] initialBytes = new byte[MAGIC_BYTE_READ_LENGTH];
            int bytesRead = inputStream.read(initialBytes, 0, MAGIC_BYTE_READ_LENGTH);

            // Check if we could read enough bytes
            if (bytesRead < MAGIC_BYTE_OFFSET + MP4_MAGIC_BYTES_FTYP.length) {
                log.warn("Could not read enough bytes ({}) for magic byte check.", bytesRead);
                return false; // File too small or read error
            }

            // Extract the bytes at the expected offset
            byte[] bytesToCheck = Arrays.copyOfRange(initialBytes, MAGIC_BYTE_OFFSET, MAGIC_BYTE_OFFSET + MP4_MAGIC_BYTES_FTYP.length);

            // Compare with expected magic bytes
            boolean match = Arrays.equals(MP4_MAGIC_BYTES_FTYP, bytesToCheck);
            if (!match && log.isDebugEnabled()) {
                log.debug("Magic byte validation failed for file content.");
            }
            return match;
        }
    }
}
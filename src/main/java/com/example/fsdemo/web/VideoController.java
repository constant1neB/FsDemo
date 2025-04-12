package com.example.fsdemo.web;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.AppUserRepository;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.service.VideoStorageException;
import com.example.fsdemo.service.VideoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);

    private final VideoStorageService storageService;
    private final VideoRepository videoRepository;
    private final AppUserRepository appUserRepository;

    // Dependencies injected via constructor
    public VideoController(VideoStorageService storageService,
                           VideoRepository videoRepository,
                           AppUserRepository appUserRepository) {
        this.storageService = storageService;
        this.videoRepository = videoRepository;
        this.appUserRepository = appUserRepository;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional // Ensures DB operation rolls back if storage fails (satisfies storage failure test)
    public ResponseEntity<VideoResponse> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            Authentication authentication) {

        // 1. Get Authenticated User
        String username = authentication.getName();
        AppUser owner = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        // 2. Basic Validation (Satisfies invalid file type test)
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File cannot be empty");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("video/")) {
            log.warn("Invalid file type uploaded by {}: {}", username, contentType);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file type. Only video files are allowed.");
        }

        // 3. Sanitize Filename (Satisfies path traversal test)
        String originalFilenameRaw = file.getOriginalFilename();
        if (originalFilenameRaw == null || originalFilenameRaw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name is missing.");
        }

        if (originalFilenameRaw.matches(".*\\p{Cntrl}.*"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid characters in filename.");

        String sanitizedFilename = StringUtils.cleanPath(originalFilenameRaw);

        log.debug("Raw filename: '{}', Sanitized filename: '{}'", originalFilenameRaw, sanitizedFilename);

        if (sanitizedFilename.contains("..") || sanitizedFilename.startsWith("/") || sanitizedFilename.startsWith("~")) {
            log.warn("Attempted path traversal by user {}: {}", authentication.getName(), originalFilenameRaw);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid characters in filename.");
        }

        // Explicit size validation
        if (file.getSize() > 100 * 1024 * 1024) { // 100MB
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File size exceeds maximum limit of 100MB");
        }

        String storagePath;
        try {
            // 4. Store the file using the (mocked in test) service
            // storageService throws VideoStorageException on failure (satisfies storage failure test)
            storagePath = storageService.store(file, owner.getId());
            log.debug("Storage path received: '{}'", storagePath);
        } catch (VideoStorageException e) {
            log.error("Storage failed for user {}: {}", username, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store video", e);
        } catch (IllegalArgumentException e) {
            log.error("Error processing filename for user {}: {}", username, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file details provided.", e);
        }

        Video video = new Video(
                owner,
                sanitizedFilename,
                description,
                Instant.now(),
                storagePath,
                file.getSize()
        );

        log.debug("Attempting to save video metadata: ID={}, Owner={}, Filename={}, Desc={}, Path={}, Size={}",
                video.getId(), // Will be null before save
                video.getOwner().getUsername(),
                video.getOriginalFilename(), //after sanitization
                video.getDescription(),
                video.getStoragePath(),
                video.getFileSize());

        // 6. Save metadata to database
        Video savedVideo = videoRepository.save(video);

        // 7. Create Response DTO (Satisfies successful response test)
        VideoResponse responseDto = new VideoResponse(
                savedVideo.getId(),
                savedVideo.getOriginalFilename(),
                savedVideo.getDescription(),
                savedVideo.getOwner().getUsername(), // Fetch username from saved entity
                savedVideo.getFileSize()
        );

        // 8. Return "Created" response
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }
}

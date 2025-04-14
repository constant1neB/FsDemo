package com.example.fsdemo.web;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.AppUserRepository;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.service.VideoStorageException;
import com.example.fsdemo.service.VideoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value; // For file size limit
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException; // For magic bytes check
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private static final Logger log = LoggerFactory.getLogger(VideoController.class);

    private final VideoStorageService storageService;
    private final VideoRepository videoRepository;
    private final AppUserRepository appUserRepository;

    // Inject max file size from properties
    @Value("${video.upload.max-size-mb:40}") // Default 40MB
    private long maxFileSizeMb;

    // Define allowed file properties
    private static final String ALLOWED_EXTENSION = ".mp4";
    private static final String ALLOWED_CONTENT_TYPE = "video/mp4"; // Be specific now
    // MP4 Magic Bytes (common signatures)
    // ftypisom (ISO Base Media file format)
    private static final byte[] MP4_MAGIC_BYTES_FTYP = new byte[]{0x66, 0x74, 0x79, 0x70};
    // Other potential starting bytes for MP4 (like ftypmp42, ftypiso4, etc.) might exist,
    // but checking for 'ftyp' at offset 4 is common. We'll check the first few bytes match.
    private static final int MAGIC_BYTE_OFFSET = 4; // 'ftyp' typically starts at byte offset 4
    private static final int MAGIC_BYTE_READ_LENGTH = 8; // Read enough bytes to check offset 4

    public VideoController(VideoStorageService storageService,
                           VideoRepository videoRepository,
                           AppUserRepository appUserRepository) {
        this.storageService = storageService;
        this.videoRepository = videoRepository;
        this.appUserRepository = appUserRepository;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional // Rollback DB changes if any step fails (like storage)
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
        // Check for control characters or path traversal attempts in the *original* name
        if (originalFilenameRaw.matches(".*\\p{Cntrl}.*") || originalFilenameRaw.contains("..") || originalFilenameRaw.contains("/") || originalFilenameRaw.contains("\\")) {
            log.warn("Upload rejected for user {}: Invalid characters detected in original filename '{}'.", username, originalFilenameRaw);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid characters in original filename.");
        }
        // Clean the path just in case (though we don't use it directly for storage path)
        String sanitizedOriginalFilename = StringUtils.cleanPath(originalFilenameRaw);
        log.debug("Sanitized original filename for validation: '{}'", sanitizedOriginalFilename);


        // Extension Check (on sanitized original filename)
        if (!sanitizedOriginalFilename.toLowerCase().endsWith(ALLOWED_EXTENSION)) {
            log.warn("Upload rejected for user {}: Invalid file extension in '{}'. Expected '{}'.", username, sanitizedOriginalFilename, ALLOWED_EXTENSION);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file type. Only " + ALLOWED_EXTENSION + " files are allowed.");
        }

        // Content-Type Check (Be specific)
        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            log.warn("Upload rejected for user {}: Invalid content type '{}'. Expected '{}'. (Filename: {})", username, contentType, ALLOWED_CONTENT_TYPE, sanitizedOriginalFilename);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid content type detected. Expected " + ALLOWED_CONTENT_TYPE + ".");
        }

        // Magic Byte Check (More reliable than content-type/extension)
        try {
            if (!hasMp4MagicBytes(file)) {
                log.warn("Upload rejected for user {}: File failed magic byte validation. (Filename: {}, ContentType: {})", username, sanitizedOriginalFilename, contentType);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File content does not appear to be a valid MP4 video.");
            }
            log.debug("Magic byte validation passed for file from user {}", username);
        } catch (IOException e) {
            log.error("IOException during magic byte check for user {}: {}", username, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading file for validation.", e);
        }
        // *** TODO: Add Antivirus Scan Hook here if integrating ***

        // 3. Generate Secure Filename (UUID) - AFTER validation passes
        String generatedFilename = UUID.randomUUID().toString() + ALLOWED_EXTENSION;
        log.info("Validation passed. Generated secure filename {} for original file '{}' uploaded by user {}", generatedFilename, sanitizedOriginalFilename, username);


        String storagePath;
        try {
            // 4. Store the file using the GENERATED filename
            log.debug("Calling storage service to store file for user {} with generated filename {}", username, generatedFilename);
            storagePath = storageService.store(file, owner.getId(), generatedFilename);
            log.debug("Storage service returned path: '{}' for generated filename: '{}'", storagePath, generatedFilename);
            // We assume storagePath might be different from generatedFilename (e.g., includes subdirs)
            // If storageService guarantees it only returns the filename, storagePath could be just generatedFilename;

        } catch (VideoStorageException e) {
            log.error("Storage failed for user {} (generated filename {}): {}", username, generatedFilename, e.getMessage(), e);
            // Keep Internal Server Error for storage issues
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store video", e);
        } catch (IllegalArgumentException e) {
            // Catch potential issues from filename generation/validation if any slip through
            log.error("Internal error processing generated filename for user {}: {}", username, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error processing file details.", e);
        }

        // 5. Create Video Entity (Using generated filename, NOT original)
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

        log.debug("Attempting to save video metadata: Owner={}, GeneratedFilename={}, Desc={}, Path={}, Size={}, MimeType={}",
                video.getOwner().getUsername(),
                video.getGeneratedFilename(),
                video.getDescription(),
                video.getStoragePath(),
                video.getFileSize(),
                video.getMimeType());

        // 6. Save metadata to database
        Video savedVideo = videoRepository.save(video);
        log.info("Successfully saved video metadata for ID: {}, Generated Filename: {}", savedVideo.getId(), savedVideo.getGeneratedFilename());


        // 7. Create Response DTO (using the generated filename)
        VideoResponse responseDto = new VideoResponse(
                savedVideo.getId(),
                savedVideo.getGeneratedFilename(), // Return the generated name
                savedVideo.getDescription(),
                savedVideo.getOwner().getUsername(),
                savedVideo.getFileSize()
        );

        // 8. Return "Created" response
        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
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
            if (!match) {
                log.debug("Magic byte mismatch. Expected: {}, Found: {}",
                        bytesToHex(MP4_MAGIC_BYTES_FTYP), bytesToHex(bytesToCheck));
            }
            return match;
        }
    }

    // Helper to convert byte array to hex string for logging
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    // === TODO: Add other endpoints (GET /api/videos, GET /api/videos/{id}, PUT, DELETE, POST /process) ===
}
package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.*;
import com.example.fsdemo.web.dto.VideoDownloadDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@Service
public class VideoManagementServiceImpl implements VideoManagementService {

    private static final Logger log = LoggerFactory.getLogger(VideoManagementServiceImpl.class);
    private static final String MP4_EXTENSION = ".mp4";
    private static final String VIDEO_NOT_FOUND_MESSAGE = "Video not found";
    private static final String VIDEO_FILE_NOT_FOUND_MESSAGE = "Video file not found in storage";
    private static final String VIDEO_FILE_NOT_READABLE_MESSAGE = "Video file not found or readable in storage";
    private static final String ERROR_RETRIEVING_VIDEO_MESSAGE = "Error retrieving video file from storage";
    private static final String UNEXPECTED_ERROR_RETRIEVING_VIDEO_MESSAGE = "Unexpected error retrieving video file";
    private static final String FORBIDDEN_MESSAGE_OWNER_ACTION = "User does not have permission to perform this action on the video";
    private static final String MISSING_STORAGE_PATH_MESSAGE = "Video file path/key is missing";

    private final VideoStorageService storageService;
    private final VideoRepository videoRepository;
    private final AppUserRepository appUserRepository;
    private final VideoSecurityService videoSecurityService;
    private final VideoUploadValidator videoUploadValidator;

    public VideoManagementServiceImpl(VideoStorageService storageService,
                                      VideoRepository videoRepository,
                                      AppUserRepository appUserRepository,
                                      VideoSecurityService videoSecurityService,
                                      VideoUploadValidator videoUploadValidator) {
        this.storageService = storageService;
        this.videoRepository = videoRepository;
        this.appUserRepository = appUserRepository;
        this.videoSecurityService = videoSecurityService;
        this.videoUploadValidator = videoUploadValidator;
    }

    // Public service methods

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Video uploadVideo(MultipartFile file, String description, String username) {
        log.info("Upload process started in service layer for user: {}", username);
        AppUser owner = appUserRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("Upload failed: User '{}' not found.", username);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found");
                });

        videoUploadValidator.validate(file);
        log.debug("Validation passed for file from user {}", username);

        String generatedFilename = UUID.randomUUID() + MP4_EXTENSION;
        log.info("Generated secure filename {} for file uploaded by user {}", generatedFilename, username);

        String storagePath = storageService.store(file, owner.getId(), generatedFilename);
        log.debug("File stored successfully at path: {}", storagePath);

        Video video = new Video(
                owner,
                description,
                Instant.now(),
                storagePath,
                file.getSize(),
                file.getContentType()
        );

        Video savedVideo = videoRepository.save(video);
        log.info("Successfully saved video metadata for ID: {}", savedVideo.getId());
        return savedVideo;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Video> listUserVideos(String username, Pageable pageable) {
        return videoRepository.findByOwnerUsername(username, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public VideoDownloadDetails prepareVideoDownload(Long videoId, String username) {
        log.debug("Prepare LATEST download process started for video ID: {} by user: {}", videoId, username);
        Video video = findAndAuthorizeVideo(videoId, username, "VIEW_LATEST");
        String latestStoragePath = determineDownloadPath(video); // Get path (processed or original)
        return prepareDownloadInternal(video, latestStoragePath, "latest");
    }

    @Override
    @Transactional(readOnly = true)
    public VideoDownloadDetails prepareOriginalVideoDownload(Long videoId, String username) {
        log.debug("Prepare ORIGINAL download process started for video ID: {} by user: {}", videoId, username);
        Video video = findAndAuthorizeVideo(videoId, username, "VIEW_ORIGINAL");
        String originalStoragePath = video.getStoragePath(); // Get original path directly
        validateStoragePathPresence(originalStoragePath, videoId, "Original"); // Validate it exists
        return prepareDownloadInternal(video, originalStoragePath, "original");
    }

    @Override
    @Transactional(readOnly = true)
    public Video getVideoForViewing(Long videoId, String username) {
        log.debug("Get video details request for video ID: {} by user: {}", videoId, username);
        return findAndAuthorizeVideo(videoId, username, "VIEW");
    }

    @Override
    @Transactional(readOnly = true)
    public void authorizeVideoProcessing(Long videoId, String username) {
        log.debug("Authorize video processing request for video ID: {} by user: {}", videoId, username);
        findAndAuthorizeVideo(videoId, username, "PROCESS");
    }

    @Override
    @Transactional
    public Video updateVideoDescription(Long videoId, String newDescription, String username) {
        log.debug("Update description process started for video ID: {} by user: {}", videoId, username);
        Video video = findAndAuthorizeVideo(videoId, username, "MODIFY");
        video.setDescription(newDescription != null ? newDescription : "");
        Video savedVideo = videoRepository.save(video);
        log.info("Successfully updated description for video ID: {}", savedVideo.getId());
        return savedVideo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVideo(Long videoId, String username) {
        log.debug("Delete process started for video ID: {} by user: {}", videoId, username);
        Video video = findAndAuthorizeVideo(videoId, username, "DELETE");

        String originalStoragePath = video.getStoragePath();
        String processedStoragePath = video.getProcessedStoragePath();

        try {
            videoRepository.delete(video);
            log.info("Successfully deleted video metadata from database for ID: {}", videoId);
        } catch (Exception e) {
            log.error("Failed to delete video record from database for ID: {}. Proceeding with file cleanup.", videoId, e);
        }

        // Best effort file deletion
        deleteFileFromStorage(originalStoragePath, videoId, "original");
        deleteFileFromStorage(processedStoragePath, videoId, "processed");

        log.info("Successfully processed delete request in service layer for video ID: {}", videoId);
    }

    // Private helper methods

    /**
     * Refactored internal method to prepare download details.
     *
     * @param video            The authorized Video entity.
     * @param storagePathToUse The specific storage path (original or latest processed) to load.
     * @param filenamePrefix   The prefix for the generated download filename ("original" or "latest").
     * @return VideoDownloadDetails DTO.
     */
    private VideoDownloadDetails prepareDownloadInternal(Video video, String storagePathToUse, String filenamePrefix) {
        log.debug("Preparing download internal for video ID: {}, using path: {}, prefix: {}", video.getId(), storagePathToUse, filenamePrefix);
        // 1. Load resource using the provided path
        Resource resource = loadResourceOrThrow(storagePathToUse, video.getId());

        // 2. Generate download filename
        String downloadFilename = filenamePrefix + "-" + UUID.randomUUID() + MP4_EXTENSION;

        // 3. Get Content Length (best effort)
        Long contentLength = getContentLengthSafely(resource, video.getId());

        // 4. Return details
        return new VideoDownloadDetails(
                resource,
                downloadFilename,
                video.getMimeType() != null ? video.getMimeType() : "application/octet-stream",
                contentLength
        );
    }

    /**
     * Finds a Video entity by its ID or throws a 404 ResponseStatusException if not found.
     */
    private Video findVideoByIdOrThrowNotFound(Long videoId) {
        return videoRepository.findById(videoId)
                .orElseThrow(() -> {
                    log.warn("Video lookup failed: Video not found for ID: {}", videoId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_NOT_FOUND_MESSAGE);
                });
    }

    /**
     * Finds a video by ID and authorizes the user based on ownership.
     */
    private Video findAndAuthorizeVideo(Long videoId, String username, String actionDescription) {
        log.debug("Attempting to find and authorize video ID: {} for user: {} (Action: {})", videoId, username, actionDescription);
        Video video = findVideoByIdOrThrowNotFound(videoId);

        if (!videoSecurityService.isOwner(videoId, username)) {
            log.warn("Authorization failed: User '{}' forbidden to {} video ID: {}", username, actionDescription, videoId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, FORBIDDEN_MESSAGE_OWNER_ACTION);
        }

        log.debug("Authorization successful for user '{}' to {} video ID: {}", username, actionDescription, videoId);
        return video;
    }

    /**
     * Determines the correct storage path (original or processed) for downloading the "latest" version.
     * The path returned should be relative to the storage root, suitable for `storageService.load()`.
     */
    private String determineDownloadPath(Video video) {
        String pathToDownload;
        // Prefer processed path if status is READY and path exists and is not blank
        if (video.getStatus() == Video.VideoStatus.READY && video.getProcessedStoragePath() != null &&
                !video.getProcessedStoragePath().isBlank()) {
            pathToDownload = video.getProcessedStoragePath(); // e.g., "processed/uuid.mp4"
            log.debug("Determined download path (latest) for video {}: Processed ({})", video.getId(), pathToDownload);
        } else {
            // Fallback to original path
            pathToDownload = video.getStoragePath(); // e.g., "uuid.mp4"
            log.debug("Determined download path (latest) for video {}: Original ({})", video.getId(), pathToDownload);
        }

        validateStoragePathPresence(pathToDownload, video.getId(), "Latest effective"); // Validate determined path
        return pathToDownload;
    }

    /**
     * Validates that a storage path string is not null or blank.
     *
     * @throws ResponseStatusException if validation fails.
     */
    private void validateStoragePathPresence(String storagePath, Long videoId, String pathType) {
        if (storagePath == null || storagePath.isBlank()) {
            log.error("Could not determine {} download path for video ID {}: Storage path is missing or blank.",
                    pathType, videoId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, MISSING_STORAGE_PATH_MESSAGE);
        }
    }


    /**
     * Loads a resource from the storage service or throws appropriate exceptions.
     * Expects storagePath to be relative to the storage service's root.
     */
    private Resource loadResourceOrThrow(String storagePath, Long videoId) {
        try {
            Resource resource = storageService.load(storagePath);
            if (!resource.exists() || !resource.isReadable()) {
                log.warn("Load resource check failed for video ID {}: Resource exists={}, isReadable={}. Path attempted: {}",
                        videoId, resource.exists(), resource.isReadable(), storagePath);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_FILE_NOT_READABLE_MESSAGE);
            }
            log.debug("Resource loaded successfully for video ID: {}, Path: {}", videoId, storagePath);
            return resource;
        } catch (VideoStorageException e) {
            if (isNotFoundStorageException(e)) {
                log.warn("Load resource failed for video ID {}: Storage service indicated file not found. Path attempted: {}",
                        videoId, storagePath, e);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_FILE_NOT_FOUND_MESSAGE, e);
            } else {
                log.error("Load resource failed for video ID {}: Unexpected storage service error. Path attempted: {}",
                        videoId, storagePath, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_RETRIEVING_VIDEO_MESSAGE, e);
            }
        } catch (ResponseStatusException e) { // Rethrow specific exceptions
            throw e;
        } catch (Exception e) { // Catch unexpected errors
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_RETRIEVING_VIDEO_MESSAGE, e);
        }
    }

    /**
     * Checks if a VideoStorageException's message indicates a "Not Found" scenario.
     */
    private boolean isNotFoundStorageException(VideoStorageException e) {
        if (e.getMessage() == null) return false;
        String lowerCaseMessage = e.getMessage().toLowerCase();
        return lowerCaseMessage.contains("could not read file") ||
                lowerCaseMessage.contains("not found") ||
                lowerCaseMessage.contains("does not exist");
    }

    /**
     * Safely attempts to get the content length of a resource.
     */
    private Long getContentLengthSafely(Resource resource, Long videoId) {
        try {
            return resource.contentLength();
        } catch (IOException e) {
            log.warn("Could not determine content length for download (Video ID: {}): {}", videoId, e.getMessage());
            return null;
        }
    }

    /**
     * Deletes a file from storage using the provided storage path (relative to storage root).
     * Logs errors without throwing exceptions to allow the primary operation to proceed.
     */
    private void deleteFileFromStorage(String storagePath, Long videoId, String fileType) {
        if (storagePath == null || storagePath.isBlank()) {
            log.trace("No {} storage path found for video ID {}, skipping file deletion.", fileType, videoId);
            return;
        }
        log.debug("Attempting to delete {} video file for ID: {} using path: {}", fileType, videoId, storagePath);
        try {
            storageService.delete(storagePath);
            log.info("Successfully deleted {} video file for ID: {} using path: {}", fileType, videoId, storagePath);
        } catch (VideoStorageException e) {
            if (isNotFoundStorageException(e)) {
                log.warn("Attempted to delete non-existent {} file from storage. VideoID: {}, Path: {}, Reason: {}",
                        fileType, videoId, storagePath, e.getMessage());
            } else {
                log.warn("Failed to delete {} file from storage. Orphaned file might exist. VideoID: {}, Path: {}, Reason: {}",
                        fileType, videoId, storagePath, e.getMessage());
            }
        } catch (Exception e) {
            log.error("Unexpected error deleting {} file from storage. Orphaned file might exist. VideoID: {}, Path: {}",
                    fileType, videoId, storagePath, e);
        }
    }
}
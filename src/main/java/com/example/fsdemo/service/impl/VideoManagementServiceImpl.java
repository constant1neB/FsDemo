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

import java.io.IOException;
import java.time.Instant;
import java.util.List;
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
    public List<Video> listUserVideos(String username) {
        log.debug("Listing videos for user: {}", username);
        return videoRepository.findByOwnerUsername(username);
    }

    @Override
    @Transactional(readOnly = true)
    public VideoDownloadDetails prepareVideoDownload(Long videoId, String username) {
        log.debug("Prepare download process started for video ID: {} by user: {}", videoId, username);

        // 1. Find and authorize video for viewing (ownership required)
        Video video = findAndAuthorizeVideo(videoId, username, "VIEW");

        // 2. Determine path
        String pathToDownload = determineDownloadPath(video);

        // 3. Load resource
        Resource resource = loadResourceOrThrow(pathToDownload, videoId);

        // 4. Generate download filename
        String downloadFilename = UUID.randomUUID() + MP4_EXTENSION;
        log.info("Generated download filename {} for video ID: {}", downloadFilename, videoId);

        // 5. Get Content Length (best effort)
        Long contentLength = getContentLengthSafely(resource, videoId);

        // 6. Return details
        return new VideoDownloadDetails(
                resource,
                downloadFilename,
                video.getMimeType() != null ? video.getMimeType() : "application/octet-stream",
                contentLength
        );
    }

    @Override
    @Transactional
    public Video updateVideoDescription(Long videoId, String newDescription, String username) {
        log.debug("Update description process started for video ID: {} by user: {}", videoId, username);

        // 1. Find and authorize video for modification (ownership required)
        Video video = findAndAuthorizeVideo(videoId, username, "MODIFY");

        // 2. Update description
        video.setDescription(newDescription != null ? newDescription : "");

        // 3. Save and return
        Video savedVideo = videoRepository.save(video);
        log.info("Successfully updated description for video ID: {}", savedVideo.getId());
        return savedVideo;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteVideo(Long videoId, String username) {
        log.debug("Delete process started for video ID: {} by user: {}", videoId, username);

        // 1. Find and authorize video for deletion (ownership required)
        Video video = findAndAuthorizeVideo(videoId, username, "DELETE");

        // 2. Get storage paths
        String originalStoragePath = video.getStoragePath();
        String processedStoragePath = video.getProcessedStoragePath();

        // 3. Delete from DB
        try {
            videoRepository.delete(video);
            log.info("Successfully deleted video metadata from database for ID: {}", videoId);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete video record", e);
        }

        // 4. Delete files from storage (best effort)
        deleteFileFromStorage(originalStoragePath, videoId, "original");
        deleteFileFromStorage(processedStoragePath, videoId, "processed");

        log.info("Successfully processed delete request in service layer for video ID: {}", videoId);
    }

    // Authorization/retrieval methods

    @Override
    @Transactional(readOnly = true)
    public Video getVideoForViewing(Long videoId, String username) {
        // Delegate to the consolidated helper
        return findAndAuthorizeVideo(videoId, username, "VIEW");
    }

    @Override
    @Transactional(readOnly = true)
    public void authorizeVideoProcessing(Long videoId, String username) {
        // Delegate to the consolidated helper (ignore return value)
        findAndAuthorizeVideo(videoId, username, "PROCESS");
    }

    // Private helper methods

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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, VideoManagementServiceImpl.FORBIDDEN_MESSAGE_OWNER_ACTION);
        }

        log.debug("Authorization successful for user '{}' to {} video ID: {}", username, actionDescription, videoId);
        return video;
    }

    /**
     * Determines the correct storage path (original or processed) for downloading.
     */
    private String determineDownloadPath(Video video) {
        String pathToDownload;
        if (video.getStatus() == Video.VideoStatus.READY && video.getProcessedStoragePath() != null &&
                !video.getProcessedStoragePath().isBlank()) {
            pathToDownload = video.getProcessedStoragePath();
        } else {
            pathToDownload = video.getStoragePath();
        }

        if (pathToDownload == null || pathToDownload.isBlank()) {
            log.error("Could not determine download path for video ID {}: Storage path/key is missing or blank. Status: {}",
                    video.getId(), video.getStatus());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Video file path/key is missing");
        }
        return pathToDownload;
    }

    /**
     * Loads a resource from the storage service or throws appropriate exceptions.
     */
    private Resource loadResourceOrThrow(String storagePath, Long videoId) {
        try {
            Resource resource = storageService.load(storagePath);

            // Check existence and readability after successful load attempt
            if (!resource.exists() || !resource.isReadable()) {
                log.warn("Load resource check failed for video ID {}: Resource exists={}, isReadable={}",
                        videoId, resource.exists(), resource.isReadable());
                // Treat as Not Found if checks fail after load
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_FILE_NOT_READABLE_MESSAGE);
            }

            log.debug("Resource loaded successfully for video ID: {}", videoId);
            return resource;

        } catch (VideoStorageException e) {
            // Check if the specific exception implies "Not Found"
            if (isNotFoundStorageException(e)) {
                log.warn("Load resource failed for video ID {}: Storage service indicated file not found.",
                        videoId, e);
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, VIDEO_FILE_NOT_FOUND_MESSAGE, e);
            } else {
                // Log other storage exceptions as errors before throwing 500
                log.error("Load resource failed for video ID {}: Unexpected storage service error.",
                        videoId, e);
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_RETRIEVING_VIDEO_MESSAGE, e);
            }
        } catch (ResponseStatusException e) {
            // Rethrow ResponseStatusExceptions (like the 404 from exists/readable check) directly
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_ERROR_RETRIEVING_VIDEO_MESSAGE, e);
        }
    }

    /**
     * Checks if a VideoStorageException's message indicates a "Not Found" scenario.
     * Encapsulates the fragile message checking.
     */
    private boolean isNotFoundStorageException(VideoStorageException e) {
        if (e.getMessage() == null) {
            return false;
        }
        // Keep the message checks, but isolated here
        return e.getMessage().contains("Could not read file") ||
                e.getMessage().contains("not found") ||
                e.getMessage().contains("Not found or not readable");
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


    private void deleteFileFromStorage(String storagePath, Long videoId, String fileType) {
        if (storagePath == null || storagePath.isBlank()) {
            log.trace("No {} storage path found for video ID {}, skipping file deletion.", fileType, videoId);
            return;
        }
        log.debug("Attempting to delete {} video file for ID: {}", fileType, videoId);
        try {
            storageService.delete(storagePath);
            log.info("Successfully deleted {} video file for ID: {}", fileType, videoId);
        } catch (VideoStorageException e) {
            log.warn("Failed to delete {} file from storage after DB record deletion. Orphaned file likely exists. VideoID: {}, Reason: {}",
                    fileType, videoId, e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error deleting {} file from storage after DB record deletion. Orphaned file likely exists. VideoID: {}",
                    fileType, videoId, e);
        }
    }
}
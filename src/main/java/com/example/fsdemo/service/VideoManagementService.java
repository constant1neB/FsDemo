// FILE: src/main/java/com/example/fsdemo/service/VideoManagementService.java
// No changes needed in the interface itself based on the refactoring request.
// It already defines prepareVideoDownload and prepareOriginalVideoDownload.
package com.example.fsdemo.service;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.web.dto.VideoDownloadDetails;
import com.example.fsdemo.exceptions.VideoValidationException;
import com.example.fsdemo.exceptions.VideoStorageException;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface VideoManagementService {

    /**
     * Handles the entire video upload process including validation, storage, and metadata creation.
     *
     * @param file        The uploaded video file.
     * @param description Optional video description.
     * @param username    The username of the owner.
     * @return The created and saved Video entity.
     * @throws VideoValidationException If validation fails.
     * @throws VideoStorageException    If storage fails.
     * @throws ResponseStatusException  If the user is not found or other issues occur.
     */
    Video uploadVideo(MultipartFile file, String description, String username);

    /**
     * Prepares the necessary details for downloading the latest available video file
     * (processed if READY, otherwise original), performing permission checks.
     *
     * @param videoId  The ID of the video to download.
     * @param username The username requesting the download.
     * @return A DTO containing the resource, filename, mime type, and content length.
     * @throws ResponseStatusException If video not found, user forbidden, or file unavailable.
     */
    VideoDownloadDetails prepareVideoDownload(Long videoId, String username);

    /**
     * Prepares the necessary details for downloading the ORIGINAL video file,
     * performing permission checks.
     *
     * @param videoId  The ID of the video to download.
     * @param username The username requesting the download.
     * @return A DTO containing the resource, filename, mime type, and content length.
     * @throws ResponseStatusException If video not found, user forbidden, or file unavailable.
     */
    VideoDownloadDetails prepareOriginalVideoDownload(Long videoId, String username);

    /**
     * Finds a video by ID and verifies if the specified user has permission to view it.
     *
     * @param videoId  The ID of the video.
     * @param username The username requesting access.
     * @return The Video entity if found and authorized.
     * @throws ResponseStatusException if not found or not authorized.
     */
    Video getVideoForViewing(Long videoId, String username);

    /**
     * Retrieves a list of videos owned by the specified user.
     *
     * @param username The username of the owner.
     * @return A list of Video entities owned by the user.
     */
    List<Video> listUserVideos(String username);

    /**
     * Verifies if the specified user has permission to process the video (is owner).
     * Throws an exception if the video is not found or the user is not authorized.
     *
     * @param videoId  The ID of the video.
     * @param username The username requesting access.
     * @throws ResponseStatusException if not found or not authorized.
     */
    void authorizeVideoProcessing(Long videoId, String username);

    /**
     * Updates the description of a video after performing permission checks.
     *
     * @param videoId        The ID of the video to update.
     * @param newDescription The new description for the video.
     * @param username       The username requesting the update.
     * @return The updated Video entity.
     * @throws ResponseStatusException If video not found or user forbidden.
     */
    Video updateVideoDescription(Long videoId, String newDescription, String username);

    /**
     * Handles the deletion of a video, including metadata and associated files.
     *
     * @param videoId  The ID of the video to delete.
     * @param username The username requesting the deletion.
     * @throws ResponseStatusException If video not found or user forbidden.
     */
    void deleteVideo(Long videoId, String username);
}
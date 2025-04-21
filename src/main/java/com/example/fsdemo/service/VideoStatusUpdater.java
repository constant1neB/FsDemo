package com.example.fsdemo.service;

/**
 * Service responsible for managing the lifecycle status of Video entities
 * in a transactionally safe manner.
 */
public interface VideoStatusUpdater {

    /**
     * Updates the status of a video to PROCESSING in a new transaction.
     * Clears the processedStoragePath.
     * Throws IllegalStateException if the video cannot transition to PROCESSING
     * (e.g., already PROCESSING or FAILED).
     *
     * @param videoId The ID of the video to update.
     * @throws IllegalStateException If the video is not found or cannot transition to PROCESSING.
     */
    void updateStatusToProcessing(Long videoId);

    /**
     * Updates the status of a video to READY in a new transaction.
     * Sets the processedStoragePath.
     * Throws IllegalStateException if the video cannot transition to READY
     * (e.g., not in PROCESSING state).
     *
     * @param videoId              The ID of the video to update.
     * @param processedStoragePath The storage path of the successfully processed video file.
     * @throws IllegalStateException If the video is not found or cannot transition to READY.
     */
    void updateStatusToReady(Long videoId, String processedStoragePath);

    /**
     * Updates the status of a video to FAILED in a new transaction.
     * Clears the processedStoragePath.
     * Should only update if the current status is PROCESSING.
     * Logs errors if update fails but does not throw exceptions to avoid masking original processing error.
     *
     * @param videoId The ID of the video to update.
     */
    void updateStatusToFailed(Long videoId);

}
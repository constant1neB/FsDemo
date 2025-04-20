package com.example.fsdemo.service;

public interface VideoStatusUpdater {

    /**
     * Updates the status of a video to FAILED in a new transaction.
     * Should only update if the current status is PROCESSING.
     *
     * @param videoId The ID of the video to update.
     */
    void updateStatusToFailed(Long videoId);

}
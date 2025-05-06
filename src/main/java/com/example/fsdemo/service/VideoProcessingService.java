package com.example.fsdemo.service;

import com.example.fsdemo.web.dto.EditOptions;

public interface VideoProcessingService {

    /**
     * Asynchronously orchestrates the video editing process based on the provided options.
     * This method is designed to run in a background thread.
     * During processing, it manages temporary files and updates the video's status
     * (e.g., to PROCESSING, READY, or FAILED).
     * If exceptions occur during async processing (like IO, timeouts, or FFmpeg errors),
     * they are logged, the video status is updated to FAILED, and then a
     * FfmpegProcessingException is re-thrown.This re-thrown exception is
     * typically caught by the application's global AsyncUncaughtExceptionHandler.
     *
     * @param videoId  The ID of the video to process.
     * @param options  The editing options (cut times, mute, resolution, etc.).
     * @param username The username of the user initiating the request (for logging/auditing).
     */
    void processVideoEdits(Long videoId, EditOptions options, String username);
}
package com.example.fsdemo.service;

import com.example.fsdemo.web.EditOptions;

public interface VideoProcessingService {

    /**
     * Asynchronously processes video edits based on the provided options.
     * This method should run in a background thread.
     *
     * @param videoId The ID of the video to process.
     * @param options The editing options (cut times, mute, resolution, etc.).
     * @param username The username of the user initiating the request (for logging/auditing).
     */
    void processVideoEdits(Long videoId, EditOptions options, String username);
}
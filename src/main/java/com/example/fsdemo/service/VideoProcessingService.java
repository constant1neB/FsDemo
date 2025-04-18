package com.example.fsdemo.service;

import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.web.dto.EditOptions;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

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

    /**
     * Executes the FFmpeg process using ProcessBuilder.
     * Handles stream reading and timeout.
     *
     * @param command The command list to execute.
     * @param videoId The ID of the video being processed (for logging).
     * @throws IOException               If ProcessBuilder fails to start or stream reading fails.
     * @throws InterruptedException      If waiting for the process is interrupted.
     * @throws FfmpegProcessingException If FFmpeg returns non-zero exit code or another processing error occurs.
     * @throws TimeoutException          If FFmpeg process or stream reading exceeds timeout.
     */
    void executeFfmpegProcess(List<String> command, Long videoId)
            throws IOException, InterruptedException, FfmpegProcessingException, TimeoutException;
}
package com.example.fsdemo.service;

import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.web.dto.EditOptions;
import net.bramp.ffmpeg.builder.FFmpegBuilder;

import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

public interface FfmpegService {

    /**
     * Builds an FFmpeg command based on the provided options, input, and output paths.
     *
     * @param tempInputPath The path to the temporary input video file.
     * @param tempOutputPath The path for the temporary output video file.
     * @param options The editing options.
     * @param videoId The ID of the video (for logging/context).
     * @param transactionContextInfo Context information for logging (e.g., transaction name).
     * @return A configured FFmpegBuilder instance.
     */
    FFmpegBuilder buildFfmpegCommand(
            Path tempInputPath, Path tempOutputPath, EditOptions options, Long videoId, String transactionContextInfo);

    /**
     * Executes a pre-configured FFmpeg job with a timeout.
     *
     * @param builder The FFmpegBuilder instance representing the command to execute.
     * @param videoId The ID of the video (for logging/context).
     * @param transactionContextInfo Context information for logging (e.g., transaction name).
     * @throws FfmpegProcessingException If FFmpeg execution fails.
     * @throws TimeoutException If FFmpeg execution times out.
     * @throws InterruptedException If the execution thread is interrupted.
     */
    void executeFfmpegJob(FFmpegBuilder builder, Long videoId, String transactionContextInfo)
            throws FfmpegProcessingException, TimeoutException, InterruptedException;
}
package com.example.fsdemo.service.impl;

import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.service.FfmpegService;
import com.example.fsdemo.web.dto.EditOptions;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.builder.FFmpegOutputBuilder;
import net.bramp.ffmpeg.job.FFmpegJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class FfmpegServiceImpl implements FfmpegService {

    private static final Logger log = LoggerFactory.getLogger(FfmpegServiceImpl.class);

    private final FFmpegExecutor ffmpegExecutor;
    private final AsyncTaskExecutor asyncTaskExecutor;
    private final long ffmpegTimeoutSeconds;

    public FfmpegServiceImpl(
            FFmpegExecutor ffmpegExecutor,
            AsyncTaskExecutor asyncTaskExecutor,
            @Value("${ffmpeg.timeout.seconds:120}") long ffmpegTimeoutSeconds
    ) {
        this.ffmpegExecutor = ffmpegExecutor;
        this.asyncTaskExecutor = asyncTaskExecutor;
        this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
    }

    @Override
    public FFmpegBuilder buildFfmpegCommand(
            Path tempInputPath, Path tempOutputPath, EditOptions options, Long videoId, String transactionContextInfo) {
        String logPrefix = String.format(
                "[FfmpegService]%s", transactionContextInfo != null ? "[" + transactionContextInfo + "]" : "");
        log.debug("{} Building FFmpeg command for video ID: {}", logPrefix, videoId);

        FFmpegBuilder builder = new FFmpegBuilder()
                .setVerbosity(FFmpegBuilder.Verbosity.INFO)
                .overrideOutputFiles(true);

        configureInput(builder, tempInputPath, options);

        FFmpegOutputBuilder outputBuilder = builder.addOutput(tempOutputPath.toString());
        configureOutput(outputBuilder, options, videoId, logPrefix);
        outputBuilder.done();

        if (log.isDebugEnabled()) {
            log.debug("{} FFmpeg command (bramp): {}", logPrefix, String.join(" ", builder.build()));
        }
        return builder;
    }

    private void configureInput(FFmpegBuilder builder, Path tempInputPath, EditOptions options) {
        if (options.cutStartTime() != null && options.cutStartTime() >= 0) {
            builder.setStartOffset((long) (options.cutStartTime() * 1_000_000_000L), TimeUnit.NANOSECONDS);
        }
        builder.addInput(tempInputPath.toString());
    }

    private void configureOutput(
            FFmpegOutputBuilder outputBuilder, EditOptions options, Long videoId, String logPrefix) {
        configureOutputDuration(outputBuilder, options, videoId, logPrefix);
        configureOutputResolution(outputBuilder, options);
        configureOutputAudio(outputBuilder, options);
        configureOutputVideoCodec(outputBuilder);
    }

    private void configureOutputDuration(
            FFmpegOutputBuilder outputBuilder, EditOptions options, Long videoId, String logPrefix) {
        Double cutStartTimeOpt = options.cutStartTime();
        Double cutEndTimeOpt = options.cutEndTime();

        if (cutEndTimeOpt != null && cutEndTimeOpt >= 0) {

            double effectiveStartTimeForDurationCalc = (cutStartTimeOpt != null && cutStartTimeOpt >= 0) ? cutStartTimeOpt : 0.0;

            if (cutEndTimeOpt > effectiveStartTimeForDurationCalc) {
                double durationSeconds = cutEndTimeOpt - effectiveStartTimeForDurationCalc;
                outputBuilder.setDuration((long) (durationSeconds * 1_000_000_000L), TimeUnit.NANOSECONDS);
                log.debug("{} Setting output duration to {}s (effective start: {}s, specified end: {}s) for video ID: {}",
                        logPrefix, durationSeconds, effectiveStartTimeForDurationCalc, cutEndTimeOpt, videoId);
            } else {
                log.warn("{} Specified cut end time ({}) is not validly after the effective start time ({}). " +
                                "Output duration will not be explicitly set based on cutEndTime for video ID: {}.",
                        logPrefix, cutEndTimeOpt, effectiveStartTimeForDurationCalc, videoId);
            }
        }
    }

    private void configureOutputResolution(FFmpegOutputBuilder outputBuilder, EditOptions options) {
        if (options.targetResolutionHeight() != null && options.targetResolutionHeight() > 0) {
            outputBuilder.setVideoFilter("scale=-2:" + options.targetResolutionHeight());
        }
    }

    private void configureOutputAudio(FFmpegOutputBuilder outputBuilder, EditOptions options) {
        outputBuilder.setAudioCodec("copy");
        if (options.mute() != null && options.mute()) {
            outputBuilder.disableAudio();
        }
    }

    private void configureOutputVideoCodec(FFmpegOutputBuilder outputBuilder) {
        outputBuilder.setVideoCodec("libx265")
                .addExtraArgs("-tag:v", "hvc1")
                .setPreset("medium")
                .setConstantRateFactor(23);
    }

    @Override
    public void executeFfmpegJob(FFmpegBuilder builder, Long videoId, String transactionContextInfo)
            throws FfmpegProcessingException, TimeoutException, InterruptedException {
        String logPrefix = String.format(
                "[FfmpegService]%s", transactionContextInfo != null ? "[" + transactionContextInfo + "]" : "");
        log.info("{} Submitting FFmpeg job for video ID: {}", logPrefix, videoId);

        FFmpegJob job = ffmpegExecutor.createJob(builder);
        Future<?> ffmpegExecutionFuture = asyncTaskExecutor.submit(job);

        try {
            ffmpegExecutionFuture.get(this.ffmpegTimeoutSeconds, TimeUnit.SECONDS);
            log.info("{} FFmpeg job completed successfully (within timeout) for video ID: {}", logPrefix, videoId);

        } catch (TimeoutException e) {
            ffmpegExecutionFuture.cancel(true);
            throw e;

        } catch (InterruptedException e) {
            ffmpegExecutionFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw e;

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("{} Exception during FFmpeg execution for video ID: {}", logPrefix, videoId, cause);

            String errorMessage = getErrorMessage(videoId, cause);
            throw new FfmpegProcessingException(errorMessage, cause);
        }
    }

    private static String getErrorMessage(Long videoId, Throwable cause) {
        String errorMessage;

        if (cause instanceof IOException && cause.getMessage() != null && cause.getMessage().contains("ffmpeg returned non-zero exit status")) {
            errorMessage = "FFmpeg process failed for video ID " + videoId + ". Cause: " + cause.getMessage();
        } else if (cause != null) {
            errorMessage = "Unexpected cause during FFmpeg execution for video ID " + videoId + ": " + cause.getMessage();
        } else {
            errorMessage = "Unknown error during FFmpeg execution for video ID " + videoId;
        }
        return errorMessage;
    }
}
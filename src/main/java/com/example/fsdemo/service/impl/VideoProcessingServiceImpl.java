package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.VideoProcessingService;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.service.VideoStatusUpdater;
import com.example.fsdemo.web.dto.EditOptions;
import jakarta.annotation.PostConstruct;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import net.bramp.ffmpeg.builder.FFmpegOutputBuilder;
import net.bramp.ffmpeg.job.FFmpegJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class VideoProcessingServiceImpl implements VideoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingServiceImpl.class);
    private static final String PROCESSED_SUBDIR = "processed";

    private final VideoRepository videoRepository;
    private final VideoStorageService videoStorageService;
    private final VideoStatusUpdater videoStatusUpdater;
    private final FFmpegExecutor ffmpegExecutor;
    private final AsyncTaskExecutor asyncTaskExecutor;
    private final Path processedStorageLocation;
    private final Path temporaryStorageLocation;
    private final long ffmpegTimeoutSeconds;

    private record TempFiles(Path inputPath, Path outputPath) {}

    @Autowired
    public VideoProcessingServiceImpl(
            VideoRepository videoRepository,
            VideoStorageService videoStorageService,
            VideoStatusUpdater videoStatusUpdater,
            FFmpegExecutor ffmpegExecutor,
            AsyncTaskExecutor asyncTaskExecutor,
            @Value("${video.storage.processed.path}") String processedPath,
            @Value("${video.storage.temp.path}") String tempPath,
            @Value("${ffmpeg.timeout.seconds:120}") long ffmpegTimeoutSeconds
    ) {
        this.videoRepository = videoRepository;
        this.videoStorageService = videoStorageService;
        this.videoStatusUpdater = videoStatusUpdater;
        this.ffmpegExecutor = ffmpegExecutor;
        this.asyncTaskExecutor = asyncTaskExecutor;
        this.processedStorageLocation = validateStoragePath(processedPath, "Processed storage");
        this.temporaryStorageLocation = validateStoragePath(tempPath, "Temporary storage");
        this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
    }

    @PostConstruct
    private void initialize() {
        try {
            Files.createDirectories(this.processedStorageLocation);
            Files.createDirectories(this.temporaryStorageLocation);
            log.info("Processed storage directory initialized");
            log.info("Temporary storage directory initialized");
        } catch (IOException e) {
            throw new VideoStorageException("Could not initialize storage directories", e);
        }
    }

    @Override
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processVideoEdits(Long videoId, EditOptions options, String username) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        log.info("[Async][TX:{}] Starting processing check for video ID: {} by user: {}", txName, videoId, username);

        validateEditOptions(options);

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> {
                    log.error("[Async][TX:{}] Video not found at start of processing task: {}", txName, videoId);
                    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Video not found for processing: " + videoId);
                });

        log.debug("[Async][TX:{}] Proceeding with processing logic for video ID: {}", txName, videoId);

        TempFiles tempFiles = null;
        boolean processingSucceeded = false;
        try {
            tempFiles = prepareTemporaryFiles(video, videoId, txName);
            FFmpegBuilder builder = buildFFmpegCommand(tempFiles.inputPath(), tempFiles.outputPath(), options, videoId, txName);
            executeFFmpegJobWithTimeout(builder, videoId, txName);
            handleSuccessfulProcessing(tempFiles.outputPath(), videoId, txName);
            processingSucceeded = true;

        } catch (Exception e) {
            handleProcessingFailure(videoId, e, txName);

            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new FfmpegProcessingException("Video processing was interrupted for video ID " + videoId, e);
            }

            throw new FfmpegProcessingException("Video processing failed for video ID " + videoId, e);

        } finally {
            if (tempFiles != null) {
                cleanupTemporaryFiles(tempFiles.inputPath(), tempFiles.outputPath(), processingSucceeded, videoId, txName);
            }
        }
    }

    private FFmpegBuilder buildFFmpegCommand(Path tempInputPath, Path tempOutputPath, EditOptions options, Long videoId, String txName) {
        log.debug("[Async][TX:{}] Building FFmpeg command for video ID: {}", txName, videoId);
        FFmpegBuilder builder = new FFmpegBuilder()
                .setVerbosity(FFmpegBuilder.Verbosity.INFO)
                .overrideOutputFiles(true);

        configureInput(builder, tempInputPath, options);

        FFmpegOutputBuilder outputBuilder = builder.addOutput(tempOutputPath.toString());
        configureOutput(outputBuilder, options, videoId, txName);
        outputBuilder.done();

        if (log.isDebugEnabled()) {
            log.debug("[Async][TX:{}] FFmpeg command (bramp): {}", txName, String.join(" ", builder.build()));
        }
        return builder;
    }

    private void configureInput(FFmpegBuilder builder, Path tempInputPath, EditOptions options) {
        if (options.cutStartTime() != null && options.cutStartTime() >= 0) {
            builder.setStartOffset(options.cutStartTime().longValue(), TimeUnit.SECONDS);
        }
        builder.addInput(tempInputPath.toString());
    }

    private void configureOutput(FFmpegOutputBuilder outputBuilder, EditOptions options, Long videoId, String txName) {
        configureOutputDuration(outputBuilder, options, videoId, txName);
        configureOutputResolution(outputBuilder, options);
        configureOutputAudio(outputBuilder, options);
        configureOutputVideoCodec(outputBuilder);
    }

    private void configureOutputDuration(FFmpegOutputBuilder outputBuilder, EditOptions options, Long videoId, String txName) {
        Double cutStartTime = options.cutStartTime();
        Double cutEndTime = options.cutEndTime();

        if (cutEndTime != null && cutEndTime >= 0) {
            double effectiveStartTime = (cutStartTime != null && cutStartTime >= 0) ? cutStartTime : 0.0;
            if (cutEndTime > effectiveStartTime) {
                double duration = cutEndTime - effectiveStartTime;
                outputBuilder.setDuration((long) (duration * 1000), TimeUnit.MILLISECONDS);
            } else {
                log.warn("[Async][TX:{}] Cut end time ({}) is not after cut start time ({}), ignoring end time for output duration for video ID: {}.",
                        txName, cutEndTime, effectiveStartTime, videoId);
            }
        }
    }

    private void configureOutputResolution(FFmpegOutputBuilder outputBuilder, EditOptions options) {
        if (options.targetResolutionHeight() != null && options.targetResolutionHeight() > 0) {
            outputBuilder.setVideoResolution(-2, options.targetResolutionHeight());
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

    private TempFiles prepareTemporaryFiles(Video video, Long videoId, String txName) throws IOException, VideoStorageException {
        log.debug("[Async][TX:{}] Preparing temporary files for video ID: {}", txName, videoId);
        Resource originalResource = videoStorageService.load(video.getStoragePath());
        if (!originalResource.exists() || !originalResource.isReadable()) {
            throw new VideoStorageException("Original video resource not found or not readable: " + video.getStoragePath());
        }

        String tempInputFilename = "temp-in-" + UUID.randomUUID();
        Path tempInputPath = temporaryStorageLocation.resolve(tempInputFilename).normalize().toAbsolutePath();
        if (!tempInputPath.startsWith(temporaryStorageLocation)) {
            throw new VideoStorageException("Security Error: Invalid temporary input path generated: " + tempInputPath);
        }
        try (InputStream inputStream = originalResource.getInputStream()) {
            Files.copy(inputStream, tempInputPath);
        }
        log.debug("[Async][TX:{}] Copied original video {} to temporary input: {}", txName, videoId, tempInputPath);

        String tempOutputFilename = "temp-out-" + UUID.randomUUID() + ".mp4";
        Path tempOutputPath = temporaryStorageLocation.resolve(tempOutputFilename).normalize().toAbsolutePath();
        if (!tempOutputPath.startsWith(temporaryStorageLocation)) {
            throw new VideoStorageException("Security Error: Invalid temporary output path generated: " + tempOutputPath);
        }

        return new TempFiles(tempInputPath, tempOutputPath);
    }

    private void executeFFmpegJobWithTimeout(FFmpegBuilder builder, Long videoId, String txName)
            throws FfmpegProcessingException, TimeoutException, InterruptedException {
        log.info("[Async][TX:{}] Submitting FFmpeg job for video ID: {}", txName, videoId);
        FFmpegJob job = ffmpegExecutor.createJob(builder);
        Future<?> ffmpegExecutionFuture = asyncTaskExecutor.submit(job);

        try {
            ffmpegExecutionFuture.get(this.ffmpegTimeoutSeconds, TimeUnit.SECONDS);
            log.info("[Async][TX:{}] FFmpeg job completed successfully (within timeout) for video ID: {}", txName, videoId);

        } catch (TimeoutException | InterruptedException e) {
            ffmpegExecutionFuture.cancel(true);
            throw e;

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("[Async][TX:{}] Exception during FFmpeg execution for video ID: {}", txName, videoId, cause);

            String errorMessage;
            String stderr = null;
            int exitCode = -1;

            if (cause instanceof RuntimeException && cause.getCause() instanceof IOException ioCause) {
                errorMessage = "IO Error during FFmpeg execution for video ID " + videoId + ": " + ioCause.getMessage();
                stderr = ioCause.getMessage();
                cause = ioCause;
            } else if (cause != null) {
                errorMessage = "Unexpected cause during FFmpeg execution for video ID " + videoId + ": " + cause.getMessage();
                stderr = cause.getMessage();
            } else {
                errorMessage = "Unknown error during FFmpeg execution for video ID " + videoId;
            }
            throw new FfmpegProcessingException(errorMessage, cause, exitCode, stderr);

        }
    }

    private void handleSuccessfulProcessing(Path tempOutputPath, Long videoId, String txName)
            throws IOException, VideoStorageException {
        log.debug("[Async][TX:{}] Handling successful processing for video ID: {}", txName, videoId);
        String finalProcessedFilename = "processed-" + UUID.randomUUID() + ".mp4";
        Path finalProcessedPath = processedStorageLocation.resolve(finalProcessedFilename).normalize().toAbsolutePath();
        if (!finalProcessedPath.startsWith(processedStorageLocation)) {
            throw new VideoStorageException("Security Error: Invalid final processed path generated: " + finalProcessedPath);
        }
        Files.createDirectories(finalProcessedPath.getParent());
        Files.move(tempOutputPath, finalProcessedPath);
        log.debug("[Async][TX:{}] Moved processed file from {} to {}", txName, tempOutputPath, finalProcessedPath);

        String relativeProcessedPath = Paths.get(PROCESSED_SUBDIR, finalProcessedFilename).toString()
                .replace("\\", "/");
        log.debug("[Async][TX:{}] Constructed relative path for DB storage: {}", txName, relativeProcessedPath);

        videoStatusUpdater.updateStatusToReady(videoId, relativeProcessedPath);
        log.info("[Async][TX:{}] Successfully processed video ID: {}. Status update to READY requested.", txName, videoId);
    }

    private void handleProcessingFailure(Long videoId, Throwable failureCause, String txName) {
        if (!(failureCause instanceof TimeoutException || failureCause instanceof ExecutionException ||
                failureCause instanceof InterruptedException)) {
            log.error("[Async][TX:{}] Processing failed for video ID: {}. Reason: {}",
                    txName, videoId, failureCause.getMessage(), failureCause);
        } else {
            log.error("[Async][TX:{}] Processing failed for video ID: {}. Reason: {}",
                    txName, videoId, failureCause.getMessage());
        }
        videoStatusUpdater.updateStatusToFailed(videoId);
    }

    private void cleanupTemporaryFiles(Path tempInputPath, Path tempOutputPath,
                                       boolean processingSucceeded, Long videoId, String txName) {
        log.debug("[Async][TX:{}] Cleaning up temporary files for video ID: {}", txName, videoId);
        cleanupTempFile(tempInputPath, videoId, "input");

        if (!processingSucceeded && tempOutputPath != null) {
            cleanupTempFile(tempOutputPath, videoId, "output (failed or timed out)");
        } else if (tempOutputPath != null) {
            log.trace("[Async][TX:{}] Temporary output file {} was moved, not cleaning up.", txName, tempOutputPath);
        }
    }

    private void cleanupTempFile(Path tempPath, Long videoId, String type) {
        if (tempPath != null) {
            try {
                if (Files.exists(tempPath)) {
                    Files.delete(tempPath);
                    log.debug("[Async] Deleted temporary {} file for video {}: {}",
                            type, videoId, tempPath);
                } else {
                    log.debug("[Async] Temporary {} file for video {} not found for deletion (already deleted or never created): {}",
                            type, videoId, tempPath);
                }
            } catch (IOException ioEx) {
                log.error("[Async] Failed to delete temporary {} file for video {}: {}",
                        type, videoId, tempPath, ioEx);
            } catch (Exception e) {
                log.error("[Async] Unexpected error cleaning up temporary {} file for video {}: {}",
                        type, videoId, tempPath, e);
            }
        } else {
            log.trace("[Async] Temporary {} path was null for video {}, skipping cleanup.", type, videoId);
        }
    }

    private Path validateStoragePath(String pathString, String purpose) {
        if (pathString == null || pathString.isBlank()) {
            throw new IllegalArgumentException(purpose + " path cannot be blank in configuration.");
        }
        if (pathString.contains("..")) {
            throw new IllegalArgumentException(purpose + " path configuration contains traversal patterns ('..'): " + pathString);
        }
        Path path;
        try {
            path = Paths.get(pathString).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path format configured for " + purpose + ": " + pathString, e);
        }
        log.debug("Validated {} storage path: {}", purpose, path);
        return path;
    }

    private void validateEditOptions(EditOptions options) {
        if (options == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "EditOptions cannot be null.");
        }
        if (options.cutStartTime() != null && options.cutEndTime() != null && options.cutEndTime() <= options.cutStartTime()) {
            String errorMessage = String.format(
                    "Invalid cut times: End time (%.2f) must be strictly greater than start time (%.2f).",
                    options.cutEndTime(),
                    options.cutStartTime()
            );
            log.warn("EditOptions validation failed: {}", errorMessage);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorMessage);
        }
    }
}
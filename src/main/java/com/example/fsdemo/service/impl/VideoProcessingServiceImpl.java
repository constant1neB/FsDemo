package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.FfmpegService;
import com.example.fsdemo.service.VideoProcessingService;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.service.VideoStatusUpdater;
import com.example.fsdemo.web.dto.EditOptions;
import jakarta.annotation.PostConstruct;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
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
    private final FfmpegService ffmpegService;
    private final Path processedStorageLocation;
    private final Path temporaryStorageLocation;

    private record TempFiles(Path inputPath, Path outputPath) {}

    @Autowired
    public VideoProcessingServiceImpl(
            VideoRepository videoRepository,
            VideoStorageService videoStorageService,
            VideoStatusUpdater videoStatusUpdater,
            FfmpegService ffmpegService,
            @Value("${video.storage.processed.path}") String processedPath,
            @Value("${video.storage.temp.path}") String tempPath
    ) {
        this.videoRepository = videoRepository;
        this.videoStorageService = videoStorageService;
        this.videoStatusUpdater = videoStatusUpdater;
        this.ffmpegService = ffmpegService;
        this.processedStorageLocation = validateStoragePath(processedPath, "Processed storage");
        this.temporaryStorageLocation = validateStoragePath(tempPath, "Temporary storage");
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

            FFmpegBuilder builder = ffmpegService.buildFfmpegCommand(
                    tempFiles.inputPath(), tempFiles.outputPath(), options, videoId, txName);
            ffmpegService.executeFfmpegJob(builder, videoId, txName);

            handleSuccessfulProcessing(tempFiles.outputPath(), videoId, txName);
            processingSucceeded = true;

        } catch (FfmpegProcessingException | TimeoutException | InterruptedException e) {
            handleProcessingFailure(videoId, e, txName);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new FfmpegProcessingException("Video processing failed for video ID " + videoId, e);

        } catch (VideoStorageException | IOException e) {
            handleProcessingFailure(videoId, e, txName);
            throw new FfmpegProcessingException("Video processing failed due to file operation for video ID " + videoId, e);

        } catch (Exception e) {
            handleProcessingFailure(videoId, e, txName);
            throw new FfmpegProcessingException("Unexpected error during video processing for video ID " + videoId, e);
        } finally {
            if (tempFiles != null) {
                cleanupTemporaryFiles(tempFiles.inputPath(), tempFiles.outputPath(), processingSucceeded, videoId, txName);
            }
        }
    }

    private TempFiles prepareTemporaryFiles(Video video, Long videoId, String txName)
            throws IOException, VideoStorageException {
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
        switch (failureCause) {
            case TimeoutException e ->
                    log.error("[Async][TX:{}] Processing timed out for video ID: {}. Reason: {}",
                            txName, videoId, e.getMessage());
            case FfmpegProcessingException fpe ->
                    log.error("[Async][TX:{}] FFmpeg processing failed for video ID: {}. Reason: {}. Details: ",
                            txName, videoId, fpe.getMessage(), fpe);

            case InterruptedException e -> {
                log.warn("[Async][TX:{}] Processing was interrupted for video ID: {}. Reason: {}",
                        txName, videoId, e.getMessage());
                Thread.currentThread().interrupt();
            }
            case null, default ->
                    log.error("[Async][TX:{}] Generic processing failure for video ID: {}.",
                            txName, videoId, failureCause);
        }
        videoStatusUpdater.updateStatusToFailed(videoId);
    }

    private void cleanupTemporaryFiles(Path tempInputPath, Path tempOutputPath,
                                       boolean processingSucceeded, Long videoId, String txName) {
        log.debug("[Async][TX:{}] Cleaning up temporary files for video ID: {}", txName, videoId);
        cleanupTempFile(tempInputPath, videoId, "input", txName);

        if (!processingSucceeded && tempOutputPath != null) {
            cleanupTempFile(tempOutputPath, videoId, "output (failed or timed out)", txName);
        } else if (tempOutputPath != null) {
            log.trace("[Async][TX:{}] Temporary output file {} was (expectedly) moved or already cleaned up for video ID: {}.",
                    txName, tempOutputPath, videoId);
            if (Files.exists(tempOutputPath)) {
                log.warn("[Async][TX:{}] Temporary output file {} still exists after successful processing for video ID: {}. Attempting cleanup.",
                        txName, tempOutputPath, videoId);
                cleanupTempFile(tempOutputPath, videoId, "output (unexpectedly present after success)", txName);
            }
        }
    }

    private void cleanupTempFile(Path tempPath, Long videoId, String type, String txName) {
        if (tempPath != null) {
            try {
                if (Files.exists(tempPath)) {
                    Files.delete(tempPath);
                    log.debug("[Async][TX:{}] Deleted temporary {} file for video {}: {}",
                            txName, type, videoId, tempPath);
                } else {
                    log.debug("[Async][TX:{}] Temporary {} file for video {} not found for deletion (already deleted or never created): {}",
                            txName, type, videoId, tempPath);
                }
            } catch (IOException ioEx) {
                log.error("[Async][TX:{}] Failed to delete temporary {} file for video {}: {}",
                        txName, type, videoId, tempPath, ioEx);
            } catch (Exception e) {
                log.error("[Async][TX:{}] Unexpected error cleaning up temporary {} file for video {}: {}",
                        txName, type, videoId, tempPath, e);
            }
        } else {
            log.trace("[Async][TX:{}] Temporary {} path was null for video {}, skipping cleanup.", txName, type, videoId);
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
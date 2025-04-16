package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.VideoProcessingService;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.web.dto.EditOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class VideoProcessingServiceImpl implements VideoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingServiceImpl.class);

    private final VideoRepository videoRepository;
    private final VideoStorageService videoStorageService; // To load original


    private final Path processedStorageLocation;
    private final Path temporaryStorageLocation; // For intermediate files
    private final String ffmpegExecutablePath;
    private static final long FFMPEG_TIMEOUT_SECONDS = 120;

    public VideoProcessingServiceImpl(
            VideoRepository videoRepository,
            VideoStorageService videoStorageService,
            @Value("${video.storage.processed.path:./uploads/videos/processed}") String processedPath,
            @Value("${video.storage.temp.path:./uploads/videos/temp}") String tempPath,
            @Value("${ffmpeg.path:ffmpeg}") String ffmpegPath) {
        this.videoRepository = videoRepository;
        this.videoStorageService = videoStorageService;
        this.processedStorageLocation = Paths.get(processedPath).toAbsolutePath().normalize();
        this.temporaryStorageLocation = Paths.get(tempPath).toAbsolutePath().normalize();
        this.ffmpegExecutablePath = ffmpegPath;

        try {
            Files.createDirectories(this.processedStorageLocation);
            Files.createDirectories(this.temporaryStorageLocation);
            log.info("Processed video storage directory initialized at: {}", this.processedStorageLocation);
            log.info("Temporary video storage directory initialized at: {}", this.temporaryStorageLocation);
            log.info("Using ffmpeg executable at: {}", this.ffmpegExecutablePath);
        } catch (IOException e) {
            log.error("Could not initialize processed or temporary storage directory", e);
            // Decide if this should prevent startup
            throw new VideoStorageException("Could not initialize storage directories", e);
        }
    }

    @Override
    @Async
    // Run in a new transaction to manage status updates independently
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processVideoEdits(Long videoId, EditOptions options, String username) {
        log.info("[Async] Starting processing for video ID: {} by user: {}", videoId, username);

        // Find video (within this new transaction)
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> {
                    // This shouldn't happen if controller logic is correct, but handle defensively
                    log.error("[Async] Video not found for processing: {}", videoId);
                    return new IllegalStateException("Video not found for processing: " + videoId);
                });

        // Double-check status
        if (video.getStatus() != VideoStatus.PROCESSING) {
            log.warn("[Async] Video {} is not in PROCESSING state (actual: {}). Aborting processing task.",
                    videoId, video.getStatus());
            return;
        }

        Resource originalResource = null;
        Path tempInputPath = null;
        Path tempOutputPath = null;
        String finalProcessedFilename = null;

        try {
            log.debug("[Async] Loading original resource from storage path: {}", video.getStoragePath());
            originalResource = videoStorageService.load(video.getStoragePath());
            if (!originalResource.exists() || !originalResource.isReadable()) {
                throw new VideoStorageException("Original video resource not found or not readable: " + video.getStoragePath());
            }

            String tempInputFilename = "temp-in-" + UUID.randomUUID() + "-" + video.getGeneratedFilename();
            tempInputPath = temporaryStorageLocation.resolve(tempInputFilename).normalize();
            Files.copy(originalResource.getInputStream(), tempInputPath);
            log.debug("[Async] Copied original video {} to temporary input: {}", videoId, tempInputPath);

            String tempOutputFilename = "temp-out-" + UUID.randomUUID() + ".mp4";
            tempOutputPath = temporaryStorageLocation.resolve(tempOutputFilename).normalize();

            // --- ======================= ---
            // --- FFmpeg Execution Call   ---
            // --- ======================= ---
            log.info("[Async] Starting FFmpeg processing for video ID: {}", videoId);
            log.info("[Async] Options: CutStart={}, CutEnd={}, Mute={}, Resolution={}",
                    options.cutStartTime(), options.cutEndTime(), options.mute(), options.targetResolutionHeight());
            log.info("[Async] Input Path (temp): {}", tempInputPath);
            log.info("[Async] Output Path (temp): {}", tempOutputPath);

            List<String> command = buildFfmpegCommand(tempInputPath, tempOutputPath, options);
            log.debug("[Async] FFmpeg command: {}", String.join(" ", command));

            // Call the extracted method
            executeFfmpegProcess(command, videoId);

            log.info("[Async] FFmpeg processing completed successfully for video ID: {}", videoId);
            // --- ================================= ---
            // ---         END FFmpeg Execution      ---
            // --- ================================= ---

            finalProcessedFilename = video.getId() + "-processed-" + UUID.randomUUID() + ".mp4";
            Path finalProcessedPath = processedStorageLocation.resolve(finalProcessedFilename).normalize();
            Files.createDirectories(finalProcessedPath.getParent());
            Files.move(tempOutputPath, finalProcessedPath);
            log.debug("[Async] Moved processed file from {} to {}", tempOutputPath, finalProcessedPath);

            // --- FIX: Remove redundant findById ---
            // Use the 'video' object we already fetched
            // Video videoToUpdate = videoRepository.findById(videoId)
            //         .orElseThrow(() -> new IllegalStateException("Video disappeared during processing: " + videoId));
            video.setStatus(VideoStatus.READY);
            video.setProcessedStoragePath(finalProcessedFilename);
            videoRepository.save(video); // Save the updated 'video' object
            log.info("[Async] Successfully processed video ID: {}. Status set to READY. Processed path: {}",
                    videoId, finalProcessedFilename);

        } catch (Exception e) {
            log.error("[Async] Processing failed for video ID: {}", videoId, e);
            updateStatusToFailed(videoId); // Use helper for failure update
        } finally {
            // Cleanup handles null paths gracefully
            cleanupTempFile(tempInputPath, videoId, "input");
            cleanupTempFile(tempOutputPath, videoId, "output");
            log.debug("[Async] Temporary file cleanup attempted for video ID: {}", videoId);
        }
    }
    
    public void executeFfmpegProcess(List<String> command, Long videoId)
            throws IOException, InterruptedException, RuntimeException, TimeoutException {

        Process ffmpegProcess = null;
        ExecutorService streamReaderExecutor = Executors.newFixedThreadPool(2);
        Future<String> stdErrFuture = null;
        Future<String> stdOutFuture = null;

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            ffmpegProcess = processBuilder.start();

            final Process currentProcess = ffmpegProcess;
            stdErrFuture = streamReaderExecutor.submit(() -> readStream(currentProcess.getErrorStream(), "STDERR"));
            stdOutFuture = streamReaderExecutor.submit(() -> readStream(currentProcess.getInputStream(), "STDOUT"));

            boolean finished = ffmpegProcess.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                log.error("[Async] FFmpeg process timed out after {} seconds for video ID: {}. Attempting to destroy.", FFMPEG_TIMEOUT_SECONDS, videoId);
                // No need to destroy here, finally block will handle it.
                throw new TimeoutException("FFmpeg process timed out for video ID: " + videoId); // Throw specific exception
            }

            int exitCode = ffmpegProcess.exitValue();
            String stdErrOutput = stdErrFuture.get(); // Wait for stream readers to finish
            String stdOutOutput = stdOutFuture.get();

            log.debug("[Async] FFmpeg STDOUT for video {}:\n{}", videoId, stdOutOutput);

            if (exitCode != 0) {
                log.error("[Async] FFmpeg failed with exit code {} for video ID: {}", exitCode, videoId);
                log.error("[Async] FFmpeg STDERR for video {}:\n{}", videoId, stdErrOutput);
                throw new RuntimeException("FFmpeg processing failed with exit code " + exitCode + ". Error: " + stdErrOutput); // Include stderr in exception
            }
            // Success case, exit code 0
            log.info("[Async] FFmpeg subprocess finished successfully for video ID: {}", videoId);

        } catch (InterruptedException | IOException e) {
            log.error("[Async] Error executing or waiting for FFmpeg process for video ID {}: {}", videoId, e.getMessage());
            // Re-throw IOException and InterruptedException to be handled by the main catch block
            throw e;
        } catch (Exception e) { // Catch Future.get() exceptions etc.
            log.error("[Async] Unexpected error during FFmpeg stream handling for video ID {}: {}", videoId, e.getMessage());
            throw new RuntimeException("Unexpected error during FFmpeg execution for video ID " + videoId, e);
        }
        finally {
            if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
                log.warn("[Async] Forcibly destroying lingering FFmpeg process for video ID: {}", videoId);
                ffmpegProcess.destroyForcibly();
            }
            streamReaderExecutor.shutdownNow();
        }
    }


    /**
     * Builds the FFmpeg command line arguments based on edit options.
     */
    private List<String> buildFfmpegCommand(Path inputPath, Path outputPath, EditOptions options) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutablePath);
        command.add("-y");

        if (options.cutStartTime() != null) {
            command.add("-ss");
            command.add(String.valueOf(options.cutStartTime()));
        }

        command.add("-i");
        command.add(inputPath.toString());

        if (options.cutEndTime() != null) {
            command.add("-to");
            command.add(String.valueOf(options.cutEndTime()));
        }

        List<String> videoFilters = new ArrayList<>();
        if (options.targetResolutionHeight() != null && options.targetResolutionHeight() > 0) {
            videoFilters.add("scale=-2:" + options.targetResolutionHeight());
        }

        if (!videoFilters.isEmpty()) {
            command.add("-vf");
            command.add(String.join(",", videoFilters));
        }

        if (options.mute() != null && options.mute()) {
            command.add("-an");
        } else {
            command.add("-c:a");
            command.add("copy");
        }

        command.add("-c:v");
        command.add("libx265");
        command.add("-tag:v");
        command.add("hvc1");
        command.add("-preset");
        command.add("medium");
        command.add("-crf");
        command.add("23");

        command.add(outputPath.toString());

        return command;
    }

    /**
     * Helper method to read an InputStream (like stdout or stderr) into a String.
     */
    private String readStream(InputStream inputStream, String streamName) {
        // Use try-with-resources for BufferedReader
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            // Use lines().collect() for cleaner stream reading
            String content = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            log.trace("Finished reading stream: {}", streamName); // Log when done reading
            return content;
        } catch (IOException e) {
            log.error("Error reading stream {}: {}", streamName, e.getMessage());
            return "[Error reading stream: " + e.getMessage() + "]";
        } catch (Exception e) {
            // Catch unexpected errors during stream processing
            log.error("Unexpected error reading stream {}: {}", streamName, e.getMessage(), e);
            return "[Unexpected error reading stream: " + e.getMessage() + "]";
        }
    }

    private void cleanupTempFile(Path tempPath, Long videoId, String type) {
        if (tempPath != null) {
            try {
                boolean deleted = Files.deleteIfExists(tempPath);
                if (deleted) {
                    log.debug("[Async] Deleted temporary {} file for video {}: {}", type, videoId, tempPath);
                } else {
                    log.debug("[Async] Temporary {} file for video {} not found for deletion: {}", type, videoId, tempPath);
                }
            } catch (IOException ioEx) {
                log.error("[Async] Failed to delete temporary {} file for video {}: {}", type, videoId, tempPath, ioEx);
            }
        }
    }

    /**
     * Helper method to update video status to FAILED.
     */
    private void updateStatusToFailed(Long videoId) {
        try {
            Video videoToFail = videoRepository.findById(videoId).orElse(null);
            if (videoToFail != null && videoToFail.getStatus() == VideoStatus.PROCESSING) {
                videoToFail.setStatus(VideoStatus.FAILED);
                videoToFail.setProcessedStoragePath(null);
                videoRepository.save(videoToFail);
                log.info("[Async] Set status to FAILED for video ID: {}", videoId);
            } else if (videoToFail != null) {
                log.warn("[Async] Video {} status was not PROCESSING during failure handling (actual: {}). Not setting to FAILED.", videoId, videoToFail.getStatus());
            } else {
                log.error("[Async] Video {} not found during failure handling.", videoId);
            }
        } catch (Exception updateEx) {
            log.error("[Async] CRITICAL: Failed to update status to FAILED for video ID: {}", videoId, updateEx);
        }
    }
}
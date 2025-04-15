package com.example.fsdemo.service;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.web.EditOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    // Define path for storing processed videos (can be same as original or different)
    // Make sure this directory exists or is created.
    private final Path processedStorageLocation;
    private final Path temporaryStorageLocation; // For intermediate files

    public VideoProcessingServiceImpl(
            VideoRepository videoRepository,
            VideoStorageService videoStorageService,
            @Value("${video.storage.processed.path:./uploads/videos/processed}") String processedPath,
            @Value("${video.storage.temp.path:./uploads/videos/temp}") String tempPath) {
        this.videoRepository = videoRepository;
        this.videoStorageService = videoStorageService;
        this.processedStorageLocation = Paths.get(processedPath).toAbsolutePath().normalize();
        this.temporaryStorageLocation = Paths.get(tempPath).toAbsolutePath().normalize();

        try {
            Files.createDirectories(this.processedStorageLocation);
            Files.createDirectories(this.temporaryStorageLocation);
            log.info("Processed video storage directory initialized at: {}", this.processedStorageLocation);
            log.info("Temporary video storage directory initialized at: {}", this.temporaryStorageLocation);
        } catch (IOException e) {
            log.error("Could not initialize processed or temporary storage directory", e);
            // Decide if this should prevent startup
            throw new VideoStorageException("Could not initialize storage directories", e);
        }
    }

    @Override
    @Async // Execute this method in a separate thread managed by Spring
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
            return; // Exit if status changed unexpectedly
        }

        Resource originalResource = null;
        Path tempInputPath = null;
        Path tempOutputPath = null;
        String finalProcessedFilename = null;

        try {
            // Load original video resource
            log.debug("[Async] Loading original resource from storage path: {}", video.getStoragePath());
            originalResource = videoStorageService.load(video.getStoragePath());
            if (!originalResource.exists() || !originalResource.isReadable()) {
                throw new VideoStorageException("Original video resource not found or not readable: " + video.getStoragePath());
            }

            // Prepare temporary files
            // Copy original to a temporary location for FFmpeg to work on (safer)
            String tempInputFilename = "temp-in-" + UUID.randomUUID() + "-" + video.getGeneratedFilename();
            tempInputPath = temporaryStorageLocation.resolve(tempInputFilename).normalize();
            Files.copy(originalResource.getInputStream(), tempInputPath);
            log.debug("[Async] Copied original video {} to temporary input: {}", videoId, tempInputPath);

            // Define temporary output path
            String tempOutputFilename = "temp-out-" + UUID.randomUUID() + ".mp4"; // Always output mp4 for now
            tempOutputPath = temporaryStorageLocation.resolve(tempOutputFilename).normalize();


            // --- ===================================== ---
            // --- !!! PLACEHOLDER: FFmpeg Execution !!! ---
            // --- ===================================== ---
            log.info("[Async] >>> Simulating FFmpeg processing for video ID: {} <<<", videoId);
            log.info("[Async] Options: CutStart={}, CutEnd={}, Mute={}, Resolution={}",
                    options.cutStartTime(), options.cutEndTime(), options.mute(), options.targetResolutionHeight());
            log.info("[Async] Input Path (temp): {}", tempInputPath);
            log.info("[Async] Output Path (temp): {}", tempOutputPath);

            // --- Build FFmpeg command based on options ---
            // Example using ProcessBuilder (needs error handling, stream consumption)
            // List<String> command = new ArrayList<>();
            // command.add("ffmpeg");
            // command.add("-i");
            // command.add(tempInputPath.toString());
            // // Add options based on EditOptions record...
            // if (options.mute()) { command.add("-an"); } // Mute audio
            // // Add cut options (-ss, -to or -t)
            // // Add resize options (-vf scale=...)
            // command.add(tempOutputPath.toString());
            //
            // ProcessBuilder processBuilder = new ProcessBuilder(command);
            // Process process = processBuilder.start();
            // int exitCode = process.waitFor(); // Blocking! Consume streams in separate threads
            // if (exitCode != 0) {
            //     // Read error stream and throw exception
            //     throw new RuntimeException("FFmpeg failed with exit code " + exitCode);
            // }

            // --- Simulate Success: Create a dummy output file for now ---
            // In reality, FFmpeg would create this file.
            Files.createFile(tempOutputPath); // Create empty file to simulate success
            log.info("[Async] >>> FFmpeg simulation completed successfully for video ID: {} <<<", videoId);
            // --- ================================= ---
            // ---         END FFmpeg Placeholder    ---
            // --- ================================= ---


            // Move processed file to final location
            // Generate final unique filename for the processed video
            finalProcessedFilename = video.getId() + "-processed-" + UUID.randomUUID() + ".mp4";
            Path finalProcessedPath = processedStorageLocation.resolve(finalProcessedFilename).normalize();

            // Ensure target directory exists (should be handled by constructor, but double check)
            Files.createDirectories(finalProcessedPath.getParent());

            // Move the temporary output file to the final processed storage location
            Files.move(tempOutputPath, finalProcessedPath); // Move atomically if possible
            log.debug("[Async] Moved processed file from {} to {}", tempOutputPath, finalProcessedPath);

            // Update Video entity on success
            // Fetch again to ensure we have the latest version before saving final state
            Video videoToUpdate = videoRepository.findById(videoId)
                    .orElseThrow(() -> new IllegalStateException("Video disappeared during processing: " + videoId));

            videoToUpdate.setStatus(VideoStatus.READY);
            videoToUpdate.setProcessedStoragePath(finalProcessedFilename); // Store relative path or full path based on storage strategy
            // TODO: Update duration, final file size, etc. if needed after processing
            videoRepository.save(videoToUpdate);
            log.info("[Async] Successfully processed video ID: {}. Status set to READY. Processed path: {}",
                    videoId, finalProcessedFilename);

        } catch (Exception e) {
            // Handle processing failure
            log.error("[Async] Processing failed for video ID: {}", videoId, e);

            // Attempt to update status to FAILED in a robust way
            try {
                // Fetch the latest state again in case of transaction issues
                Video videoToFail = videoRepository.findById(videoId)
                        .orElse(null); // Find but don't throw if it's gone now

                if (videoToFail != null && videoToFail.getStatus() == VideoStatus.PROCESSING) {
                    videoToFail.setStatus(VideoStatus.FAILED);
                    videoToFail.setProcessedStoragePath(null); // Ensure no processed path on failure
                    videoRepository.save(videoToFail);
                    log.info("[Async] Set status to FAILED for video ID: {}", videoId);
                } else if (videoToFail != null){
                    log.warn("[Async] Video {} status was not PROCESSING during failure handling (actual: {}). Not setting to FAILED.", videoId, videoToFail.getStatus());
                } else {
                    log.error("[Async] Video {} not found during failure handling.", videoId);
                }
            } catch (Exception updateEx) {
                // Log the exception during the FAILED status update attempt
                log.error("[Async] CRITICAL: Failed to update status to FAILED for video ID: {}", videoId, updateEx);
                // This indicates a more severe problem, potentially requiring manual intervention.
            }
        } finally {
            // Cleanup temporary files
            cleanupTempFile(tempInputPath, videoId, "input");
            cleanupTempFile(tempOutputPath, videoId, "output");
            log.debug("[Async] Temporary file cleanup attempted for video ID: {}", videoId);
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
                // Log error but don't let cleanup failure stop the main flow or fail the job status
            }
        }
    }
}
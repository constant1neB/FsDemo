package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.exceptions.FfmpegProcessingException;
import com.example.fsdemo.exceptions.StreamOutputRetrievalException;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.VideoProcessingService;
import com.example.fsdemo.service.VideoStorageService;
import com.example.fsdemo.service.VideoStatusUpdater;
import com.example.fsdemo.web.dto.EditOptions;
import jakarta.annotation.PostConstruct;
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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class VideoProcessingServiceImpl implements VideoProcessingService {

    private static final Logger log = LoggerFactory.getLogger(VideoProcessingServiceImpl.class);

    private final VideoRepository videoRepository;
    private final VideoStorageService videoStorageService;
    private final VideoStatusUpdater videoStatusUpdater;
    private final Path processedStorageLocation;
    private final Path temporaryStorageLocation;
    private final String ffmpegExecutablePath;
    private final long ffmpegTimeoutSeconds;

    @Autowired
    public VideoProcessingServiceImpl(
            VideoRepository videoRepository,
            VideoStorageService videoStorageService,
            VideoStatusUpdater videoStatusUpdater,
            @Value("${video.storage.processed.path}") String processedPath,
            @Value("${video.storage.temp.path}") String tempPath,
            @Value("${ffmpeg.path}") String ffmpegPath,
            @Value("${ffmpeg.timeout.seconds:120}") long ffmpegTimeoutSeconds
    ) {
        this.videoRepository = videoRepository;
        this.videoStorageService = videoStorageService;
        this.videoStatusUpdater = videoStatusUpdater;
        this.processedStorageLocation = validateStoragePath(processedPath, "Processed storage");
        this.temporaryStorageLocation = validateStoragePath(tempPath, "Temporary storage");
        this.ffmpegExecutablePath = validateExecutablePath(ffmpegPath);
        this.ffmpegTimeoutSeconds = ffmpegTimeoutSeconds;
    }

    @PostConstruct
    private void initialize() {
        try {
            Files.createDirectories(this.processedStorageLocation);
            Files.createDirectories(this.temporaryStorageLocation);
        } catch (IOException e) {
            throw new VideoStorageException("Could not initialize storage directories", e);
        }
    }

    @Override
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void processVideoEdits(Long videoId, EditOptions options, String username) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        log.info("[Async][TX:{}] Starting processing check for video ID: {} by user: {}", txName, videoId, username);

        validateEditOptions(options);

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> {
                    log.error("[Async][TX:{}] Video not found at start of processing task: {}", txName, videoId);
                    return new IllegalStateException("Video not found for processing: " + videoId);
                });

        log.debug("[Async][TX:{}] Proceeding with processing logic for video ID: {}", txName, videoId);

        Resource originalResource;
        Path tempInputPath = null;
        Path tempOutputPath = null;
        String finalProcessedFilename = null;

        try {
            log.debug("[Async][TX:{}] Loading original resource from storage path: {}", txName, video.getStoragePath());
            originalResource = videoStorageService.load(video.getStoragePath());
            if (!originalResource.exists() || !originalResource.isReadable()) {
                throw new VideoStorageException("Original video resource not found or not readable: " + video.getStoragePath());
            }

            // Create Temp Input File
            String tempInputFilename = "temp-in-" + UUID.randomUUID() + "-" + video.getGeneratedFilename();
            tempInputPath = temporaryStorageLocation.resolve(tempInputFilename).normalize().toAbsolutePath();
            if (!tempInputPath.startsWith(temporaryStorageLocation)) {
                throw new VideoStorageException("Security Error: Invalid temporary input path generated: " + tempInputPath);
            }
            try (InputStream inputStream = originalResource.getInputStream()) {
                Files.copy(inputStream, tempInputPath);
            }
            log.debug("[Async][TX:{}] Copied original video {} to temporary input: {}", txName, videoId, tempInputPath);

            // Prepare Temp Output Path
            String tempOutputFilename = "temp-out-" + UUID.randomUUID() + ".mp4";
            tempOutputPath = temporaryStorageLocation.resolve(tempOutputFilename).normalize().toAbsolutePath();
            if (!tempOutputPath.startsWith(temporaryStorageLocation)) {
                throw new VideoStorageException("Security Error: Invalid temporary output path generated: " + tempOutputPath);
            }

            // Execute FFmpeg
            log.info("[Async][TX:{}] Starting FFmpeg processing for video ID: {}", txName, videoId);
            List<String> command = buildFfmpegCommand(tempInputPath, tempOutputPath, options);
            if (log.isDebugEnabled()) {
                log.debug("[Async][TX:{}] FFmpeg command: {}", txName, String.join(" ", command));
            }
            executeFfmpegProcess(command, videoId); // This method now handles the process start and monitoring
            log.info("[Async][TX:{}] FFmpeg processing completed successfully for video ID: {}", txName, videoId);

            // Move Processed File
            finalProcessedFilename = video.getId() + "-processed-" + UUID.randomUUID() + ".mp4";
            Path finalProcessedPath = processedStorageLocation.resolve(finalProcessedFilename).normalize().toAbsolutePath();
            if (!finalProcessedPath.startsWith(processedStorageLocation)) {
                throw new VideoStorageException("Security Error: Invalid final processed path generated: " + finalProcessedPath);
            }
            Files.createDirectories(finalProcessedPath.getParent());
            Files.move(tempOutputPath, finalProcessedPath);
            log.debug("[Async][TX:{}] Moved processed file from {} to {}", txName, tempOutputPath, finalProcessedPath);

            // Update Status via Service
            videoStatusUpdater.updateStatusToReady(videoId, finalProcessedFilename);
            log.info("[Async][TX:{}] Successfully processed video ID: {}. Status update requested.", txName, videoId);

        } catch (InterruptedException e) {
            log.warn("[Async][TX:{}] Processing interrupted for video ID: {}", txName, videoId, e);
            Thread.currentThread().interrupt();
            videoStatusUpdater.updateStatusToFailed(videoId);
        } catch (Exception e) {
            log.error("[Async][TX:{}] Processing failed for video ID: {}", txName, videoId, e);
            videoStatusUpdater.updateStatusToFailed(videoId);
        } finally {
            cleanupTempFile(tempInputPath, videoId, "input");
            if (finalProcessedFilename == null && tempOutputPath != null) {
                cleanupTempFile(tempOutputPath, videoId, "output (failed process)");
            } else if (tempOutputPath != null) {
                log.trace("[Async][TX:{}] Temporary output file {} was moved, not cleaning up.", txName, tempOutputPath);
            }
            log.debug("[Async][TX:{}] Temporary file cleanup attempted for video ID: {}", txName, videoId);
        }
    }

    @Override
    public void executeFfmpegProcess(List<String> command, Long videoId)
            throws IOException, InterruptedException, FfmpegProcessingException, TimeoutException {

        Process ffmpegProcess = null; // Declare here for finally block
        try (ExecutorService streamReaderExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            long timeoutSeconds = this.ffmpegTimeoutSeconds;

            ProcessBuilder processBuilder = new ProcessBuilder(command);

            // Call the new method to start the process
            ffmpegProcess = startFfmpegProcess(processBuilder, videoId);

            final Process currentProcess = ffmpegProcess;
            Future<String> stdErrFuture = streamReaderExecutor.submit(() -> readStream(currentProcess.getErrorStream(), "STDERR"));
            Future<String> stdOutFuture = streamReaderExecutor.submit(() -> readStream(currentProcess.getInputStream(), "STDOUT"));

            boolean finished = ffmpegProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                handleFfmpegTimeout(ffmpegProcess, streamReaderExecutor, videoId, timeoutSeconds);
                // handleFfmpegTimeout throws TimeoutException, so execution stops here on timeout
            }

            // Process finished within timeout - retrieve outputs and handle exit code
            int exitCode = ffmpegProcess.exitValue();
            String stdErrOutput = getStreamOutput(stdErrFuture, "STDERR", videoId);
            String stdOutOutput = getStreamOutput(stdOutFuture, "STDOUT", videoId);

            handleFfmpegCompletion(exitCode, stdOutOutput, stdErrOutput, videoId);

        } catch (InterruptedException | TimeoutException | FfmpegProcessingException e) {
            // Propagate specific exceptions thrown by startFfmpegProcess or subsequent logic
            throw e;
        } catch (ExecutionException e) {
            // Handle exceptions from the stream reading futures
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("[Async] Stream reading interrupted for video ID {}", videoId);
                throw ie;
            } else if (cause instanceof IOException ioe) {
                log.error("[Async] IOException during stream reading for video ID {}", videoId, ioe);
                throw ioe;
            } else if (cause instanceof StreamOutputRetrievalException sore) {
                log.error("[Async] StreamOutputRetrievalException reading {} for video ID {}", sore.getStreamName(), videoId, sore);
                throw sore;
            }
            // Wrap any other unexpected execution exceptions
            throw new FfmpegProcessingException("Unexpected error reading FFmpeg stream output for video ID " + videoId, cause != null ? cause : e);
        } catch (Exception e) {
            // Catch-all for any other unexpected errors during execution (excluding those caught above)
            throw new FfmpegProcessingException("Unexpected error during FFmpeg process execution for video ID " + videoId, e);
        } finally {
            // Ensure process is destroyed if it's still alive (e.g., due to exception before waitFor)
            if (ffmpegProcess != null && ffmpegProcess.isAlive()) {
                log.warn("[Async] Forcibly destroying lingering FFmpeg process in finally block for video ID: {}", videoId);
                ffmpegProcess.destroyForcibly();
            }
        }
    }

    /**
     * Starts the FFmpeg process using the provided ProcessBuilder.
     * Handles potential IOException during process startup by wrapping it in FfmpegProcessingException.
     *
     * @param processBuilder The configured ProcessBuilder.
     * @param videoId        The ID of the video being processed (for logging/context).
     * @return The started Process object.
     * @throws FfmpegProcessingException if the process fails to start (e.g., executable not found).
     */
    private Process startFfmpegProcess(ProcessBuilder processBuilder, Long videoId) throws FfmpegProcessingException {
        Process ffmpegProcess;
        try {
            ffmpegProcess = processBuilder.start();
        } catch (IOException ioEx) {
            String errorMessage = "Failed to start FFmpeg process. Check path ('" + this.ffmpegExecutablePath + "') and permissions.";
            throw new FfmpegProcessingException(errorMessage, ioEx);
        }
        log.debug("[Async] Started FFmpeg process PID: {} for video ID: {}", ffmpegProcess.pid(), videoId);
        return ffmpegProcess;
    }


    // --- Helper methods (handleFfmpegTimeout, getStreamOutput, handleFfmpegCompletion, buildFfmpegCommand, readStream, cleanupTempFile) remain the same ---
    // (Keep the existing implementations of these methods)
    private void handleFfmpegTimeout(Process process, ExecutorService executor, Long videoId, long timeoutSeconds) throws TimeoutException {
        String timeoutMsg = String.format("[Async] FFmpeg process timed out after %s seconds for video ID: %s. Attempting to destroy and shutdown readers.",
                timeoutSeconds, videoId);
        log.error(timeoutMsg);

        log.warn("[Async] Timeout detected for video {}. Shutting down stream reader executor.", videoId);
        List<Runnable> tasksAwaitingExecution = executor.shutdownNow();
        if (!tasksAwaitingExecution.isEmpty()) {
            log.warn("[Async] {} stream reader tasks were awaiting execution for video {}.", tasksAwaitingExecution.size(), videoId);
        }
        try {
            if (!executor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                log.warn("[Async] Stream reader executor did not terminate quickly after shutdownNow for video {}.", videoId);
            }
        } catch (InterruptedException ie) {
            log.warn("[Async] Interrupted while waiting for stream reader executor termination for video {}.", videoId);
            Thread.currentThread().interrupt();
        }

        if (process.isAlive()) {
            log.warn("[Async] Forcibly destroying timed-out FFmpeg process for video ID: {}", videoId);
            process.destroyForcibly();
        }

        throw new TimeoutException("FFmpeg process timed out for video ID: " + videoId);
    }

    private String getStreamOutput(Future<String> future, String streamName, Long videoId)
            throws ExecutionException, InterruptedException, TimeoutException, StreamOutputRetrievalException {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException | InterruptedException e) {
            throw e;
        } catch (CancellationException e) {
            log.warn("[Async] Stream reading task for {} cancelled for video {} (likely due to timeout/shutdown): {}", streamName, videoId, e.getMessage());
            return "[" + streamName + " reading cancelled]";
        } catch (Exception e) {
            String errorMsg = String.format("Unexpected error retrieving %s output for video %d", streamName, videoId);
            throw new StreamOutputRetrievalException(errorMsg, streamName, e);
        }
    }

    private void handleFfmpegCompletion(int exitCode, String stdOutOutput, String stdErrOutput, Long videoId)
            throws FfmpegProcessingException {
        log.debug("[Async] FFmpeg STDOUT for video {}:\n{}", videoId, stdOutOutput);

        if (exitCode != 0) {
            String errorMsg = "FFmpeg processing failed with exit code " + exitCode + ".";
            log.error("[Async] {} for video ID: {}", errorMsg, videoId);
            log.error("[Async] FFmpeg STDERR for video {}:\n{}", videoId, stdErrOutput);
            throw new FfmpegProcessingException(errorMsg, exitCode, stdErrOutput);
        }
        log.info("[Async] FFmpeg subprocess finished successfully with exit code 0 for video ID: {}", videoId);
        if (log.isDebugEnabled() && stdErrOutput != null && !stdErrOutput.isBlank()) {
            log.debug("[Async] FFmpeg STDERR (on success) for video {}:\n{}", videoId, stdErrOutput);
        }
    }

    private List<String> buildFfmpegCommand(Path inputPath, Path outputPath, EditOptions options) {
        List<String> command = new ArrayList<>();
        command.add(this.ffmpegExecutablePath);
        command.add("-y");

        if (options.cutStartTime() != null && options.cutStartTime() >= 0) {
            command.add("-ss");
            command.add(String.valueOf(options.cutStartTime()));
        }
        command.add("-i");
        command.add(inputPath.toString());

        if (options.cutEndTime() != null && options.cutEndTime() >= 0) {
            if (options.cutStartTime() == null || options.cutEndTime() > options.cutStartTime()) {
                command.add("-to");
                command.add(String.valueOf(options.cutEndTime()));
            } else {
                log.warn("Cut end time ({}) is not after cut start time ({}), ignoring end time.", options.cutEndTime(), options.cutStartTime());
            }
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

    private String readStream(InputStream inputStream, String streamName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return reader.lines().collect(Collectors.joining(System.lineSeparator()));
        } catch (IOException e) {
            log.error("[Async] IOException while reading stream {}: {}", streamName, e.getMessage());
            return "[Error reading stream: " + e.getMessage() + "]";
        } catch (UncheckedIOException e) {
            log.warn("[Async] UncheckedIOException while reading stream {} (process likely terminated prematurely): {}", streamName, e.getMessage());
            return "[Stream reading interrupted: " + e.getMessage() + "]";
        } catch (Exception e) {
            log.error("[Async] Unexpected error reading stream {}: {}", streamName, e.getMessage(), e);
            return "[Unexpected error reading stream: " + e.getMessage() + "]";
        } finally {
            log.trace("Finished attempting to read stream: {}", streamName);
        }
    }

    private void cleanupTempFile(Path tempPath, Long videoId, String type) {
        if (tempPath != null) {
            try {
                if (Files.exists(tempPath)) {
                    Files.delete(tempPath);
                    log.debug("[Async] Deleted temporary {} file for video {}: {}", type, videoId, tempPath);
                } else {
                    log.debug("[Async] Temporary {} file for video {} not found for deletion (already deleted or never created): {}", type, videoId, tempPath);
                }
            } catch (IOException ioEx) {
                log.error("[Async] Failed to delete temporary {} file for video {}: {}", type, videoId, tempPath, ioEx);
            } catch (Exception e) {
                log.error("[Async] Unexpected error cleaning up temporary {} file for video {}: {}", type, videoId, tempPath, e);
            }
        } else {
            log.trace("[Async] Temporary {} path was null for video {}, skipping cleanup.", type, videoId);
        }
    }

    //    Helper methods to validate paths
    private Path validateStoragePath(String pathString, String purpose) {
        // 1. Basic null/blank check
        if (pathString == null || pathString.isBlank()) {
            throw new IllegalArgumentException(purpose + " path cannot be blank");
        }

        // 2. Block directory traversal attempts
        if (pathString.contains("../") || pathString.contains("..\\") ||
                pathString.contains("~/") || pathString.contains("~\\")) {
            throw new IllegalArgumentException(purpose + " path contains traversal patterns");
        }

        // 3. Convert to Path and normalize
        Path path;
        try {
            path = Paths.get(pathString).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid path format", e);
        }

        // 4. Verify after normalization
        String normalized = path.toString();
        if (normalized.contains("../") || normalized.contains("..\\")) {
            throw new IllegalArgumentException(
                    purpose + " path normalizes to traversal pattern: " + normalized);
        }

        return path;
    }

    private static final Pattern DANGEROUS_CHARS = Pattern.compile("[;&|<>`$()\\n\\r!\"']");

    private String validateExecutablePath(String pathString) {
        // 1. Basic checks
        if (pathString == null || pathString.isBlank()) {
            throw new IllegalArgumentException("Executable path cannot be blank");
        }

        // 2. Length check (prevent excessive input)
        if (pathString.length() > 512) {
            throw new IllegalArgumentException("Executable path too long");
        }

        // 3. Security check (no backtracking risk)
        if (DANGEROUS_CHARS.matcher(pathString).find()) {
            throw new IllegalArgumentException("Executable path contains dangerous characters");
        }

        Path path = Paths.get(pathString).normalize();

        return path.toString();
    }


    // Helper method to ensure correctness of cut time parameters
    private void validateEditOptions(EditOptions options) {
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
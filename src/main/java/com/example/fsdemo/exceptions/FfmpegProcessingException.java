package com.example.fsdemo.exceptions;

/**
 * Custom RuntimeException indicating a failure during FFmpeg process execution
 * or an unexpected result (e.g., non-zero exit code).
 */
public class FfmpegProcessingException extends RuntimeException {

    private final Integer exitCode; // Integer to allow null if exit code couldn't be determined before throwing
    private final String stderrOutput; // Store stderr for debugging

    // Constructor for general processing errors without specific exit code/stderr
    public FfmpegProcessingException(String message) {
        super(message);
        this.exitCode = null;
        this.stderrOutput = null;
    }

    // Constructor for wrapping other exceptions
    public FfmpegProcessingException(String message, Throwable cause) {
        super(message, cause);
        this.exitCode = null;
        this.stderrOutput = null;
    }

    // Constructor specifically for non-zero exit code failures
    public FfmpegProcessingException(String message, int exitCode, String stderrOutput) {
        super(message);
        this.exitCode = exitCode;
        this.stderrOutput = stderrOutput;
    }

    // Constructor for wrapping exceptions when exit code/stderr might also be known
    public FfmpegProcessingException(String message, Throwable cause, int exitCode, String stderrOutput) {
        super(message, cause);
        this.exitCode = exitCode;
        this.stderrOutput = stderrOutput;
    }

    /**
     * Gets the exit code of the FFmpeg process, if available.
     * @return The exit code as an Integer, or null if not available.
     */
    public Integer getExitCode() {
        return exitCode;
    }

    /**
     * Gets the standard error output captured from the FFmpeg process, if available.
     * @return The stderr output, or null if not captured or not available.
     */
    public String getStderrOutput() {
        return stderrOutput;
    }
}
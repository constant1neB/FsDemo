package com.example.fsdemo.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FfmpegProcessingException Tests")
class FfmpegProcessingExceptionTest {

    @Test
    @DisplayName("Constructor (message) should set message correctly")
    void constructorWithMessage() {
        String message = "Simple error";
        FfmpegProcessingException ex = new FfmpegProcessingException(message);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getExitCode()).isNull();
        assertThat(ex.getStderrOutput()).isNull();
    }

    @Test
    @DisplayName("Constructor (message, cause) should set message and cause")
    void constructorWithMessageAndCause() {
        String message = "Error with cause";
        Throwable cause = new IOException("Underlying IO error");
        FfmpegProcessingException ex = new FfmpegProcessingException(message, cause);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getExitCode()).isNull();
        assertThat(ex.getStderrOutput()).isNull();
    }

    @Test
    @DisplayName("Constructor (message, exitCode, stderr) should set properties")
    void constructorWithExitCodeAndStderr() {
        String message = "Process failed";
        int exitCode = 127;
        String stderr = "ffmpeg: command not found";
        FfmpegProcessingException ex = new FfmpegProcessingException(message, exitCode, stderr);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isNull();
        assertThat(ex.getExitCode()).isEqualTo(exitCode);
        assertThat(ex.getStderrOutput()).isEqualTo(stderr);
    }

    @Test
    @DisplayName("Constructor (message, cause, exitCode, stderr) should set all properties")
    void constructorWithAllProperties() {
        String message = "Process failed with cause";
        Throwable cause = new InterruptedException("Process interrupted");
        int exitCode = 1;
        String stderr = "Error during processing";
        FfmpegProcessingException ex = new FfmpegProcessingException(message, cause, exitCode, stderr);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getExitCode()).isEqualTo(exitCode);
        assertThat(ex.getStderrOutput()).isEqualTo(stderr);
    }
}
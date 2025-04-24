package com.example.fsdemo.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("VideoStorageException Tests")
class VideoStorageExceptionTest {

    @Test
    @DisplayName("Constructor (message) should set message correctly and cause to null")
    void constructorWithMessageOnly() {
        String message = "Failed to store video file.";
        VideoStorageException ex = new VideoStorageException(message);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("Constructor (message, cause) should set message and cause correctly")
    void constructorWithMessageAndCause() {
        String message = "Failed to delete video file due to permissions.";
        Throwable cause = new AccessDeniedException("permission denied"); // Example cause
        VideoStorageException ex = new VideoStorageException(message, cause);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("Should accept different types of Throwables as cause")
    void constructorWithDifferentCauseType() {
        String message = "Failed to read video file.";
        Throwable cause = new IOException("Disk read error"); // Another example cause
        VideoStorageException ex = new VideoStorageException(message, cause);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.getCause()).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Should function correctly with null cause in second constructor")
    void constructorWithMessageAndNullCause() {
        String message = "Storage error, unspecified cause.";
        VideoStorageException ex = new VideoStorageException(message, null);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getCause()).isNull();
    }
}
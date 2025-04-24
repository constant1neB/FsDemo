package com.example.fsdemo.exceptions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StreamOutputRetrievalException Tests")
class StreamOutputRetrievalExceptionTest {

    @Test
    @DisplayName("Constructor should set message, stream name, and cause correctly")
    void constructorWithMessageStreamNameAndCause() {
        String message = "Failed to get stream output";
        String streamName = "STDERR";
        Throwable cause = new ExecutionException("Future task failed", new IOException("Underlying IO error"));

        StreamOutputRetrievalException ex = new StreamOutputRetrievalException(message, streamName, cause);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getStreamName()).isEqualTo(streamName);
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("getStreamName should return the correct stream name")
    void getStreamNameReturnsCorrectValue() {
        String streamName = "STDOUT";
        StreamOutputRetrievalException ex = new StreamOutputRetrievalException("msg", streamName, null);
        assertThat(ex.getStreamName()).isEqualTo(streamName);
    }

    @Test
    @DisplayName("Should function correctly with null cause")
    void constructorWithNullCause() {
        String message = "Failed, no specific cause";
        String streamName = "STDERR";

        StreamOutputRetrievalException ex = new StreamOutputRetrievalException(message, streamName, null);

        assertThat(ex.getMessage()).isEqualTo(message);
        assertThat(ex.getStreamName()).isEqualTo(streamName);
        assertThat(ex.getCause()).isNull();
    }
}
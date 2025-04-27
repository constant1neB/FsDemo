package com.example.fsdemo.exceptions;

/**
 * Custom RuntimeException indicating an unexpected failure occurred while
 * attempting to retrieve the output from an asynchronous stream reading task (Future).
 * This typically wraps an unexpected exception from Future.get().
 */
public class StreamOutputRetrievalException extends RuntimeException {

    private final String streamName;

    /**
     * Constructs a new StreamOutputRetrievalException.
     *
     * @param message    The detail message.
     * @param streamName The name of the stream being retrieved ("STDOUT" or "STDERR").
     * @param cause      The original cause of the exception.
     */
    public StreamOutputRetrievalException(String message, String streamName, Throwable cause) {
        super(message, cause);
        this.streamName = streamName;
    }

    /**
     * Gets the name of the stream ("STDOUT" or "STDERR") whose output retrieval failed.
     * @return The stream name.
     */
    public String getStreamName() {
        return streamName;
    }
}
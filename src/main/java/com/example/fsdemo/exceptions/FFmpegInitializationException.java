package com.example.fsdemo.exceptions;

public class FFmpegInitializationException extends RuntimeException {

    public FFmpegInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
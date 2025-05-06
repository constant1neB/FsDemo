package com.example.fsdemo.exceptions;

public class FfmpegInitializationException extends RuntimeException {

    public FfmpegInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
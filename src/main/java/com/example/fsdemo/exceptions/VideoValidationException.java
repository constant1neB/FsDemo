package com.example.fsdemo.exceptions;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

public class VideoValidationException extends ResponseStatusException {

    public VideoValidationException(HttpStatusCode status, String reason) {
        super(status, reason);
    }

    public VideoValidationException(HttpStatusCode status, String reason, Throwable cause) {
        super(status, reason, cause);
    }
}
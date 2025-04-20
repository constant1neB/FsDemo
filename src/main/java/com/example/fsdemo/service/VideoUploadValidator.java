package com.example.fsdemo.service;

import com.example.fsdemo.exceptions.VideoValidationException;
import org.springframework.web.multipart.MultipartFile;

public interface VideoUploadValidator {

    /**
     * Validates the uploaded video file based on predefined rules (size, type, content, etc.).
     * Throws a VideoValidationException if any rule is violated.
     *
     * @param file The MultipartFile to validate.
     * @throws VideoValidationException if validation fails.
     */
    void validate(MultipartFile file) throws VideoValidationException;
}
package com.example.fsdemo.service.impl;

import com.example.fsdemo.exceptions.VideoValidationException;
import com.example.fsdemo.service.VideoUploadValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

@Component
public class VideoUploadValidatorImpl implements VideoUploadValidator {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadValidatorImpl.class);

    private static final String ALLOWED_EXTENSION = ".mp4";
    private static final String ALLOWED_CONTENT_TYPE = "video/mp4";
    private static final byte[] MP4_MAGIC_BYTES_FTYP = new byte[]{0x66, 0x74, 0x79, 0x70};
    private static final int MAGIC_BYTE_OFFSET = 4;
    private static final int MAGIC_BYTE_READ_LENGTH = 8;

    private final long maxSizeBytes;

    public VideoUploadValidatorImpl(@Value("${video.upload.max-size-mb:40}") long maxFileSizeMb) {
        this.maxSizeBytes = maxFileSizeMb * 1024 * 1024;
        log.info("VideoUploadValidator initialized with max file size: {} MB", maxFileSizeMb);
    }

    @Override
    public void validate(MultipartFile file) throws VideoValidationException {
        if (file == null || file.isEmpty()) {
            throw new VideoValidationException(HttpStatus.BAD_REQUEST, "File cannot be null or empty");
        }

        // Size Check
        if (file.getSize() > maxSizeBytes) {
            throw new VideoValidationException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File size (" + file.getSize() + " bytes) exceeds maximum limit of " + (maxSizeBytes / (1024 * 1024)) + "MB");
        }

        // Original Filename Validation
        String originalFilename = validateAndSanitizeOriginalFilename(file.getOriginalFilename());

        // Extension Check
        if (!originalFilename.toLowerCase().endsWith(ALLOWED_EXTENSION)) {
            throw new VideoValidationException(HttpStatus.BAD_REQUEST,
                    "Invalid file type. Only " + ALLOWED_EXTENSION + " files are allowed.");
        }

        // Content-Type Check
        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            log.warn("Upload rejected: Invalid content type '{}'. Expected '{}'. Original filename: '{}'",
                    contentType, ALLOWED_CONTENT_TYPE, originalFilename);
            throw new VideoValidationException(HttpStatus.BAD_REQUEST,
                    "Invalid content type detected. Expected " + ALLOWED_CONTENT_TYPE + ".");
        }

        // Magic Byte Check
        try {
            if (!hasMp4MagicBytes(file)) {
                log.warn("Upload rejected: File failed magic byte validation. ContentType: {}, Original Filename: '{}'",
                        contentType, originalFilename);
                throw new VideoValidationException(HttpStatus.BAD_REQUEST,
                        "File content does not appear to be a valid MP4 video.");
            }
            log.trace("Magic byte validation passed for file with original name '{}'", originalFilename);
        } catch (IOException e) {
            throw new VideoValidationException(HttpStatus.INTERNAL_SERVER_ERROR, "Error reading file for validation.", e);
        }

        // *** Antivirus scan hook placeholder ***

        log.debug("File validation passed for original filename: '{}'", originalFilename);
    }

    private String validateAndSanitizeOriginalFilename(String originalFilenameRaw) throws VideoValidationException {
        if (originalFilenameRaw == null || originalFilenameRaw.isBlank()) {
            throw new VideoValidationException(HttpStatus.BAD_REQUEST, "Original file name is missing or blank.");
        }
        if (originalFilenameRaw.matches(".*\\p{Cntrl}.*") || originalFilenameRaw.contains("..") || originalFilenameRaw.contains("/") || originalFilenameRaw.contains("\\")) {
            log.warn("Upload rejected: Invalid characters detected in original filename: '{}'", originalFilenameRaw);
            throw new VideoValidationException(HttpStatus.BAD_REQUEST, "Invalid characters in original filename.");
        }
        return StringUtils.cleanPath(originalFilenameRaw);
    }

    private boolean hasMp4MagicBytes(MultipartFile file) throws IOException {
        try (InputStream inputStream = file.getInputStream()) {
            byte[] initialBytes = new byte[MAGIC_BYTE_READ_LENGTH];
            int bytesRead = inputStream.read(initialBytes, 0, MAGIC_BYTE_READ_LENGTH);

            if (bytesRead < MAGIC_BYTE_OFFSET + MP4_MAGIC_BYTES_FTYP.length) {
                log.warn("Could not read enough bytes ({}) for magic byte check.", bytesRead);
                return false;
            }

            byte[] bytesToCheck = Arrays.copyOfRange(initialBytes, MAGIC_BYTE_OFFSET, MAGIC_BYTE_OFFSET + MP4_MAGIC_BYTES_FTYP.length);
            return Arrays.equals(MP4_MAGIC_BYTES_FTYP, bytesToCheck);
        }
    }
}
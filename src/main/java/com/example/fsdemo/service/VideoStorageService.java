// src/main/java/com/example/fsdemo/service/VideoStorageService.java
package com.example.fsdemo.service;

import org.springframework.web.multipart.MultipartFile;

public interface VideoStorageService {
    /**
     * Stores the uploaded video file using a generated filename.
     *
     * @param file The video file uploaded by the user.
     * @param userId The ID of the user uploading the file.
     * @param generatedFilename The UUID-based filename to use for storage.
     * @return The unique path/identifier where the file is stored (can be the same as generatedFilename or a full path).
     * @throws VideoStorageException if storing fails.
     */
    String store(MultipartFile file, Long userId, String generatedFilename) throws VideoStorageException;

    // Add load and delete later
    // Resource load(String storagePath);
    // void delete(String storagePath);
}

// src/main/java/com/example/fsdemo/service/VideoStorageService.java
package com.example.fsdemo.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

public interface VideoStorageService {
    /**
     * Stores the uploaded video file.
     *
     * @param file The video file uploaded by the user.
     * @param userId The ID of the user uploading the file.
     * @return The unique path/identifier where the file is stored.
     * @throws VideoStorageException if storing fails.
     */
    String store(MultipartFile file, Long userId) throws VideoStorageException;

}

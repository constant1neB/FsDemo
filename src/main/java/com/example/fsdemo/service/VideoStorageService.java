package com.example.fsdemo.service;

import com.example.fsdemo.exceptions.VideoStorageException;
import org.springframework.core.io.Resource; // Import Resource
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

    /**
     * Loads a file as a Spring Resource.
     *
     * @param storagePath The path returned by the store method (e.g., the generated filename or a relative path).
     * @return The Resource object representing the file.
     * @throws VideoStorageException if the file cannot be found or read.
     */
    Resource load(String storagePath) throws VideoStorageException;

    /**
     * Deletes the file associated with the given storage path.
     *
     * @param storagePath The path returned by the store method.
     * @throws VideoStorageException if deletion fails.
     */
    void delete(String storagePath) throws VideoStorageException;
}

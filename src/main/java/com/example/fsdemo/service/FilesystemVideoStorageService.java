package com.example.fsdemo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

@Service
public class FilesystemVideoStorageService implements VideoStorageService {
    private final Path rootLocation;

    // Inject the storage path from application.properties
    public FilesystemVideoStorageService(@Value("${video.storage.path}") String path) {
        this.rootLocation = Paths.get(path);
        try {
            // Create the directory if it doesn't exist
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new VideoStorageException("Could not initialize storage directory: " + path, e);
        }
    }

    @Override
    public String store(MultipartFile file, Long userId) throws VideoStorageException {
        // Basic validation
        if (file.isEmpty()) {
            throw new VideoStorageException("Failed to store empty file.");
        }

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));

        try {
            // Create a unique filename to avoid collisions and include user ID
            String filename = userId + "_" + System.currentTimeMillis() + "_" + originalFilename;
            Path destinationFile = this.rootLocation.resolve(
                            Paths.get(filename))
                    .normalize().toAbsolutePath();

            // Security check: Ensure the destination is within the root location
            if (!destinationFile.getParent().equals(this.rootLocation.toAbsolutePath())) {
                throw new VideoStorageException(
                        "Cannot store file outside designated directory structure: " + originalFilename);
            }

            // Save the file
            file.transferTo(destinationFile);

            // Return the relative path or just the filename used for storage
            return filename; // Or return destinationFile.toString(); if you need the full path

        } catch (IOException e) {
            throw new VideoStorageException("Failed to store file " + originalFilename, e);
        } catch (NullPointerException e) {
            throw new VideoStorageException("File or filename was null", e);
        }
    }

    // Implement load and delete later when needed
    // @Override public Resource load(String filename) { ... }
    // @Override public void delete(String filename) { ... }
}
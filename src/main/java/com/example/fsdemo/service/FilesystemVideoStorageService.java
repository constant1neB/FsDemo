package com.example.fsdemo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FilesystemVideoStorageService implements VideoStorageService {
    private final Path rootLocation;

    public FilesystemVideoStorageService(@Value("${video.storage.path:./uploads/videos}") String path) { // Provide a default path
        this.rootLocation = Paths.get(path).toAbsolutePath().normalize(); // Normalize path
        try {
            Files.createDirectories(rootLocation);
        } catch (IOException e) {
            throw new VideoStorageException("Could not initialize storage directory: " + this.rootLocation, e);
        }
    }

    @Override
    public String store(MultipartFile file, Long userId, String generatedFilename) throws VideoStorageException {
        // Basic validation (already done in controller mostly, but double check empty)
        if (file.isEmpty()) {
            throw new VideoStorageException("Failed to store empty file (storage service check).");
        }
        // The filename is already generated and validated by the controller
        if (generatedFilename == null || generatedFilename.isBlank() || generatedFilename.contains("/") || generatedFilename.contains("\\") || generatedFilename.contains("..")) {
            throw new VideoStorageException("Invalid generated filename received by storage service: " + generatedFilename);
        }


        try {
            Path destinationFile = this.rootLocation.resolve(generatedFilename)
                    .normalize().toAbsolutePath();

            // Security check: Ensure the destination is *exactly* within the root location
            // (normalize().toAbsolutePath() helps prevent some traversal issues)
            if (!destinationFile.getParent().equals(this.rootLocation)) {
                // This should theoretically not happen if generatedFilename is clean, but belt-and-suspenders
                throw new VideoStorageException(
                        "Security check failed: Cannot store file outside designated directory structure. Target: " + destinationFile);
            }

            // Check if file already exists (UUID collision is extremely unlikely, but check anyway)
            if (Files.exists(destinationFile)) {
                throw new VideoStorageException("File already exists (UUID collision?): " + generatedFilename);
            }

            // Save the file using transferTo
            file.transferTo(destinationFile);

            // Return the filename used for storage (which is the generated one)
            return generatedFilename;

        } catch (IOException e) {
            throw new VideoStorageException("Failed to store file " + generatedFilename, e);
        }
    }

    // Implement load and delete later
    // @Override public Resource load(String storagePath) { ... }
    // @Override public void delete(String storagePath) { ... }
}
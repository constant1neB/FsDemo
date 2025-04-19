package com.example.fsdemo.service.impl;

import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.service.VideoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FilesystemVideoStorageServiceImpl implements VideoStorageService {
    private static final Logger log = LoggerFactory.getLogger(FilesystemVideoStorageServiceImpl.class); // Add logger
    private final Path rootLocation;

    public FilesystemVideoStorageServiceImpl(@Value("${video.storage.path:./uploads/videos}") String path) {
        this.rootLocation = Paths.get(path).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootLocation);
            log.info("Video storage directory initialized at: {}", this.rootLocation); // Add log
        } catch (IOException e) {
            throw new VideoStorageException("Could not initialize storage directory: " + this.rootLocation, e);
        }
    }

    @Override
    public String store(MultipartFile file, Long userId, String generatedFilename) throws VideoStorageException {
        if (file.isEmpty()) {
            throw new VideoStorageException("Failed to store empty file (storage service check).");
        }
        if (generatedFilename == null || generatedFilename.isBlank() || generatedFilename.contains("/") || generatedFilename.contains("\\") || generatedFilename.contains("..")) {
            throw new VideoStorageException("Invalid generated filename received by storage service: " + generatedFilename);
        }

        try {
            Path destinationFile = this.rootLocation.resolve(generatedFilename)
                    .normalize().toAbsolutePath();

            if (!destinationFile.getParent().equals(this.rootLocation)) {
                throw new VideoStorageException(
                        "Security check failed: Cannot store file outside designated directory structure. Target: " + destinationFile);
            }

            if (Files.exists(destinationFile)) {
                // This is unlikely with UUIDs, but good practice
                log.warn("Attempted to store file that already exists (UUID collision?): {}", destinationFile);
                // Decide policy: overwrite or fail. Failing is safer.
                throw new VideoStorageException("File already exists: " + generatedFilename);
            }

            log.debug("Storing file {} to {}", generatedFilename, destinationFile);
            // Use try-with-resources for the input stream
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile); // Use Files.copy, transferTo can be problematic sometimes
            }
            log.info("Successfully stored file {} for user {}", generatedFilename, userId); // Log success
            return generatedFilename; // Assuming storagePath is just the filename for simplicity here

        } catch (IOException e) {// Log error details
            throw new VideoStorageException("Failed to store file " + generatedFilename, e);
        }
    }

    @Override
    public Resource load(String storagePath) throws VideoStorageException {
        try {
            Path file = rootLocation.resolve(storagePath).normalize().toAbsolutePath();
            log.debug("Attempting to load resource from path: {}", file);

            // Security check: Ensure the path is still within the root directory after resolving
            if (!file.startsWith(this.rootLocation)) {
                log.error("Security check failed: Attempt to load file outside storage root. Requested path: {}, Resolved path: {}", storagePath, file);
                throw new VideoStorageException("Security check failed: Cannot load file outside designated directory: " + storagePath);
            }

            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                log.debug("Resource loaded successfully: {}", file);
                return resource;
            } else {
                log.warn("Could not read file or file does not exist: {}", file);
                throw new VideoStorageException("Could not read file: " + storagePath);
            }
        } catch (MalformedURLException e) {
            throw new VideoStorageException("Could not read file (Malformed URL): " + storagePath, e);
        } catch (VideoStorageException e) {
            // Re-throw security exceptions directly
            throw e;
        } catch (Exception e) {
            throw new VideoStorageException("Unexpected error loading file: " + storagePath, e);
        }
    }

    @Override
    public void delete(String storagePath) throws VideoStorageException {
        try {
            Path file = rootLocation.resolve(storagePath).normalize().toAbsolutePath();
            log.debug("Attempting to delete file at path: {}", file);

            // Security check: Ensure the path is still within the root directory
            if (!file.startsWith(this.rootLocation)) {
                log.error("Security check failed: Attempt to delete file outside storage root. Requested path: {}, Resolved path: {}", storagePath, file);
                throw new VideoStorageException("Security check failed: Cannot delete file outside designated directory: " + storagePath);
            }

            if (Files.exists(file)) {
                Files.delete(file);
                log.info("Successfully deleted file: {}", file);
            } else {
                log.warn("Attempted to delete non-existent file: {}", file);
                // Decide if this is an error or acceptable (idempotency)
                // For now, let's not throw an error if it's already gone.
            }
        } catch (IOException e) {
            throw new VideoStorageException("Failed to delete file: " + storagePath, e);
        } catch (VideoStorageException e) {
            // Re-throw security exceptions directly
            throw e;
        } catch (Exception e) {
            throw new VideoStorageException("Unexpected error deleting file: " + storagePath, e);
        }
    }
}
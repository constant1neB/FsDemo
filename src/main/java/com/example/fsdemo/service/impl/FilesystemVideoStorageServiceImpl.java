package com.example.fsdemo.service.impl;

import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.service.VideoStorageService;
import jakarta.annotation.PostConstruct; // Import PostConstruct
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
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class FilesystemVideoStorageServiceImpl implements VideoStorageService {
    private static final Logger log = LoggerFactory.getLogger(FilesystemVideoStorageServiceImpl.class);
    private final Path rootLocation;

    public FilesystemVideoStorageServiceImpl(@Value("${video.storage.path}") String path) {
        this.rootLocation = Paths.get(path).toAbsolutePath().normalize();
    }

    @PostConstruct
    private void initialize() {
        try {
            Files.createDirectories(rootLocation);
            log.info("Video storage directory initialized at: {}", this.rootLocation);
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
                log.warn("Attempted to store file that already exists (UUID collision?): {}", destinationFile);
                throw new VideoStorageException("File already exists: " + generatedFilename);
            }

            log.debug("Storing file {} to {}", generatedFilename, destinationFile);
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, destinationFile);
            }
            log.info("Successfully stored file {} for user {}", generatedFilename, userId);
            return generatedFilename;

        } catch (IOException e) {
            throw new VideoStorageException("Failed to store file " + generatedFilename, e);
        }
    }

    @Override
    public Resource load(String storagePath) throws VideoStorageException {
        try {
            Path file = resolveAndValidatePath(storagePath);
            log.debug("Attempting to load resource from path: {}", file);

            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                log.debug("Resource loaded successfully: {}", file);
                return resource;
            } else {
                String reason = resource.exists() ? "not readable" : "does not exist";
                log.warn("Could not read file or file does not exist: {} ({})", file, reason);
                throw new VideoStorageException("Could not read file: " + storagePath + " (File " + reason + ")");
            }
        } catch (MalformedURLException e) {
            throw new VideoStorageException("Could not read file (Malformed URL): " + storagePath, e);
        } catch (VideoStorageException e) {
            // Re-throw specific storage exceptions from helper or checks
            throw e;
        } catch (Exception e) {
            // Catch unexpected errors during resource creation/checking
            throw new VideoStorageException("Unexpected error loading file: " + storagePath, e);
        }
    }

    @Override
    public void delete(String storagePath) throws VideoStorageException {
        try {
            Path file = resolveAndValidatePath(storagePath);
            log.debug("Attempting to delete file: {}", file);

            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("Successfully deleted file: {}", file);
            } else {
                log.warn("Attempted to delete non-existent file: {}", file);
            }
        } catch (IOException e) {
            // This might happen due to permissions issues even if the file exists
            throw new VideoStorageException("Failed to delete file due to IO error: " + storagePath, e);
        } catch (VideoStorageException e) {
            // Re-throw specific storage exceptions from helper
            throw e;
        } catch (Exception e) {
            // Catch unexpected errors
            throw new VideoStorageException("Unexpected error deleting file: " + storagePath, e);
        }
    }

    //    Helper method
    private Path resolveAndValidatePath(String storagePath) throws VideoStorageException {
        if (storagePath == null || storagePath.isBlank()) {
            throw new VideoStorageException("Storage path cannot be null or blank.");
        }
        // Basic check for path characters within the relative path itself
        if (storagePath.contains("..") || storagePath.contains("/") || storagePath.contains("\\")) {
            throw new VideoStorageException("Invalid characters found in storage path: " + storagePath);
        }

        try {
            Path resolvedPath = this.rootLocation.resolve(storagePath).normalize().toAbsolutePath();

            // Security check: Ensure the resolved path is still within the root location
            if (!resolvedPath.startsWith(this.rootLocation)) {
                log.error("Security check failed: Attempt to access file outside storage root.");
                throw new VideoStorageException("Security check failed: Cannot access file outside designated directory: " + storagePath);
            }
            return resolvedPath;
        } catch (InvalidPathException e) {
            throw new VideoStorageException("Invalid storage path provided: " + storagePath, e);
        }
    }
}
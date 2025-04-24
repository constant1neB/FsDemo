package com.example.fsdemo.service;

import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.service.impl.FilesystemVideoStorageServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FilesystemVideoStorageService Implementation Tests")
class FilesystemVideoStorageServiceImplTest {

    @TempDir
    Path tempStorageDir; // JUnit 5 creates and cleans this temp directory

    private FilesystemVideoStorageServiceImpl storageService;
    private final String testFilename = "test-video-uuid.mp4";
    private final Long testUserId = 1L;
    private MockMultipartFile testMultipartFile;
    private final byte[] fileContent = "test video content".getBytes();

    @BeforeEach
    void setUp() {
        // Initialize service with the temporary directory path
        storageService = new FilesystemVideoStorageServiceImpl(tempStorageDir.toString());
        // Call initialize manually as @PostConstruct doesn't run in plain unit tests
        ReflectionTestUtils.invokeMethod(storageService, "initialize");

        testMultipartFile = new MockMultipartFile(
                "file",
                testFilename,
                "video/mp4",
                fileContent
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        // Additional cleanup if needed, though @TempDir handles the directory itself
    }

    // --- store() Tests ---

    @Test
    @DisplayName("✅ store: Should store file successfully and return filename")
    void store_Success() throws IOException {
        String storedFilename = storageService.store(testMultipartFile, testUserId, testFilename);

        assertThat(storedFilename).isEqualTo(testFilename);
        Path expectedPath = tempStorageDir.resolve(testFilename);
        assertThat(Files.exists(expectedPath)).isTrue();
        assertThat(Files.readAllBytes(expectedPath)).isEqualTo(fileContent);
    }

    @Test
    @DisplayName("❌ store: Should throw VideoStorageException for empty file")
    void store_FailEmptyFile() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.mp4", "video/mp4", new byte[0]);

        assertThatThrownBy(() -> storageService.store(emptyFile, testUserId, "empty.mp4"))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Failed to store empty file");
    }

    @Test
    @DisplayName("❌ store: Should throw VideoStorageException for invalid generated filename (contains ..)")
    void store_FailInvalidFilenameDoubleDot() {
        assertThatThrownBy(() -> storageService.store(testMultipartFile, testUserId, "../" + testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Invalid generated filename received by storage service");
    }

    @Test
    @DisplayName("❌ store: Should throw VideoStorageException for invalid generated filename (contains /)")
    void store_FailInvalidFilenameSlash() {
        assertThatThrownBy(() -> storageService.store(testMultipartFile, testUserId, "subdir/" + testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Invalid generated filename received by storage service");
    }

    @Test
    @DisplayName("❌ store: Should throw VideoStorageException if file already exists")
    void store_FailFileAlreadyExists() {
        // Store it once
        storageService.store(testMultipartFile, testUserId, testFilename);
        assertThat(Files.exists(tempStorageDir.resolve(testFilename))).isTrue();

        // Try to store again
        assertThatThrownBy(() -> storageService.store(testMultipartFile, testUserId, testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("File already exists");
    }

    @Test
    @DisplayName("❌ store: Should wrap IOException during file copy")
    void store_FailIOExceptionDuringCopy() throws IOException {
        // Mock the multipart file to throw IOException on getInputStream
        MockMultipartFile failingFile = mock(MockMultipartFile.class);
        when(failingFile.isEmpty()).thenReturn(false);
        when(failingFile.getInputStream()).thenThrow(new IOException("Simulated disk write error"));

        assertThatThrownBy(() -> storageService.store(failingFile, testUserId, testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Failed to store file")
                .hasCauseInstanceOf(IOException.class);

        // Ensure file was not created partially
        assertThat(Files.exists(tempStorageDir.resolve(testFilename))).isFalse();
    }

    // --- load() Tests ---

    @Test
    @DisplayName("✅ load: Should load existing file as Resource")
    void load_Success() throws IOException {
        // Store a file first
        storageService.store(testMultipartFile, testUserId, testFilename);
        Path storedPath = tempStorageDir.resolve(testFilename);
        assertThat(Files.exists(storedPath)).isTrue();

        Resource resource = storageService.load(testFilename);

        assertThat(resource.exists()).isTrue();
        assertThat(resource.isReadable()).isTrue();
        assertThat(resource.getFilename()).isEqualTo(testFilename);
        assertThat(resource.contentLength()).isEqualTo(fileContent.length);
        try (InputStream is = resource.getInputStream()) {
            assertThat(is.readAllBytes()).isEqualTo(fileContent);
        }
    }

    @Test
    @DisplayName("❌ load: Should throw VideoStorageException if file not found")
    void load_FailNotFound() {
        String nonExistentFilename = "not-real.mp4";
        assertThatThrownBy(() -> storageService.load(nonExistentFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Could not read file: " + nonExistentFilename);
    }

    @Test
    @DisplayName("❌ load: Should throw VideoStorageException for invalid path characters (..)")
    void load_FailInvalidPathDoubleDot() {
        assertThatThrownBy(() -> storageService.load("../" + testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Invalid characters found in storage path");
    }

    @Test
    @DisplayName("❌ load: Should throw VideoStorageException for invalid path characters (/)")
    void load_FailInvalidPathSlash() {
        assertThatThrownBy(() -> storageService.load("subdir/" + testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Invalid characters found in storage path");
    }

    @Test
    @DisplayName("❌ load: Should throw VideoStorageException for null path")
    void load_FailNullPath() {
        assertThatThrownBy(() -> storageService.load(null))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Storage path cannot be null or blank");
    }

    @Test
    @DisplayName("❌ load: Should throw VideoStorageException for blank path")
    void load_FailBlankPath() {
        assertThatThrownBy(() -> storageService.load("  "))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Storage path cannot be null or blank");
    }

    // --- delete() Tests ---

    @Test
    @DisplayName("✅ delete: Should delete existing file")
    void delete_Success() {
        // Store a file first
        storageService.store(testMultipartFile, testUserId, testFilename);
        Path storedPath = tempStorageDir.resolve(testFilename);
        assertThat(Files.exists(storedPath)).isTrue();

        storageService.delete(testFilename);

        assertThat(Files.exists(storedPath)).isFalse();
    }

    @Test
    @DisplayName("✅ delete: Should not throw if file does not exist")
    void delete_SuccessFileNotFound() {
        String nonExistentFilename = "not-real-to-delete.mp4";
        assertThat(Files.exists(tempStorageDir.resolve(nonExistentFilename))).isFalse();

        assertThatCode(() -> storageService.delete(nonExistentFilename))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("❌ delete: Should throw VideoStorageException for invalid path characters (..)")
    void delete_FailInvalidPathDoubleDot() {
        assertThatThrownBy(() -> storageService.delete("../" + testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Invalid characters found in storage path");
    }

    @Test
    @DisplayName("❌ delete: Should throw VideoStorageException for invalid path characters (/)")
    void delete_FailInvalidPathSlash() {
        assertThatThrownBy(() -> storageService.delete("subdir/" + testFilename))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Invalid characters found in storage path");
    }

    @Test
    @DisplayName("❌ delete: Should throw VideoStorageException for null path")
    void delete_FailNullPath() {
        assertThatThrownBy(() -> storageService.delete(null))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Storage path cannot be null or blank");
    }

    @Test
    @DisplayName("❌ delete: Should throw VideoStorageException for blank path")
    void delete_FailBlankPath() {
        assertThatThrownBy(() -> storageService.delete("   "))
                .isInstanceOf(VideoStorageException.class)
                .hasMessageContaining("Storage path cannot be null or blank");
    }

    // --- initialize() Tests ---
    // initialize() is called in setup, we can test failure by manipulating the path temporarily

    @Test
    @DisplayName("❌ initialize: Should throw VideoStorageException if directory creation fails")
    void initialize_FailDirectoryCreation() throws IOException {
        // Create a file where the directory should be to cause Files.createDirectories to fail
        Path conflictingFile = tempStorageDir.getParent().resolve("conflictingFile");
        Files.createFile(conflictingFile);

        try {
            FilesystemVideoStorageServiceImpl failingService = new FilesystemVideoStorageServiceImpl(conflictingFile.toString());
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(failingService, "initialize"))
                    .isInstanceOf(VideoStorageException.class)
                    .hasMessageContaining("Could not initialize storage directory");
        } finally {
            Files.deleteIfExists(conflictingFile); // Clean up
        }
    }
}
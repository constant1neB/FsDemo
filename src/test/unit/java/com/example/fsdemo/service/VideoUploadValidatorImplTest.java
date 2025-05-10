package com.example.fsdemo.service;

import com.example.fsdemo.exceptions.VideoValidationException;
import com.example.fsdemo.service.impl.VideoUploadValidatorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("VideoUploadValidatorImpl Tests")
class VideoUploadValidatorImplTest {

    private VideoUploadValidatorImpl validator;
    private final long maxFileSizeMb = 5;
    private final long maxFileSizeBytes = maxFileSizeMb * 1024 * 1024;

    // Valid MP4 magic bytes structure (simplified for test)
    // Needs 8 bytes: [?, ?, ?, ?, 'f', 't', 'y', 'p']
    private final byte[] validMp4MagicBytes = new byte[]{
            0x00, 0x00, 0x00, 0x18, // Size (example)
            0x66, 0x74, 0x79, 0x70  // 'ftyp'
    };
    // Invalid magic bytes (e.g., a text file signature)
    private final byte[] invalidMagicBytes = new byte[]{
            0x54, 0x68, 0x69, 0x73, // "This"
            0x20, 0x69, 0x73, 0x20  // " is "
    };

    @BeforeEach
    void setUp() {
        validator = new VideoUploadValidatorImpl(maxFileSizeMb);
    }

    private MockMultipartFile createMockFile(String originalFilename, String contentType, byte[] content) {
        return new MockMultipartFile("file", originalFilename, contentType, content);
    }

    private MockMultipartFile createValidTestFile() {
        return createMockFile("valid_video.mp4", "video/mp4", validMp4MagicBytes);
    }

    private MockMultipartFile createValidTestFile(String filename) {
        return createMockFile(filename, "video/mp4", validMp4MagicBytes);
    }

    @Nested
    @DisplayName("Validation Success Scenarios")
    class ValidationSuccess {
        @Test
        @DisplayName("✅ Should pass for valid file")
        void validate_ValidFile_ShouldPass() {
            MockMultipartFile validFile = createValidTestFile();
            assertThatCode(() -> validator.validate(validFile)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ Should pass for file exactly at max size")
        void validate_MaxSizeFile_ShouldPass() {
            byte[] content = new byte[(int) maxFileSizeBytes];
            // Add magic bytes to make it seem valid otherwise
            System.arraycopy(validMp4MagicBytes, 0, content, 0, validMp4MagicBytes.length);
            MockMultipartFile maxSizeFile = createMockFile("max_size.mp4", "video/mp4", content);

            assertThatCode(() -> validator.validate(maxSizeFile)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("✅ Should pass for filename with spaces and mixed case")
        void validate_FilenameWithSpacesAndCase_ShouldPass() {
            MockMultipartFile file = createValidTestFile("My Valid Video File.Mp4");
            assertThatCode(() -> validator.validate(file)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Validation Failure Scenarios")
    class ValidationFailure {

        @Test
        @DisplayName("❌ Should fail for null file")
        void validate_NullFile_ShouldThrowException() {
            assertThatThrownBy(() -> validator.validate(null))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("File cannot be null or empty");
        }

        @Test
        @DisplayName("❌ Should fail for empty file")
        void validate_EmptyFile_ShouldThrowException() {
            MockMultipartFile emptyFile = createMockFile("empty.mp4", "video/mp4", new byte[0]);
            assertThatThrownBy(() -> validator.validate(emptyFile))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("File cannot be null or empty");
        }

        @Test
        @DisplayName("❌ Should fail for file exceeding max size")
        void validate_FileSizeExceeded_ShouldThrowException() {
            byte[] content = new byte[(int) maxFileSizeBytes + 1];
            MockMultipartFile largeFile = createMockFile("too_large.mp4", "video/mp4", content);

            assertThatThrownBy(() -> validator.validate(largeFile))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.PAYLOAD_TOO_LARGE)
                    .hasMessageContaining("exceeds maximum limit");
        }

        @ParameterizedTest
        @ValueSource(strings = {"video.txt", "image.jpeg", "archive.zip", "noextension"})
        @DisplayName("❌ Should fail for invalid file extensions")
        void validate_InvalidExtension_ShouldThrowException(String filename) {
            MockMultipartFile invalidExtFile = createMockFile(filename, "video/mp4", validMp4MagicBytes);
            assertThatThrownBy(() -> validator.validate(invalidExtFile))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("Invalid file type. Only .mp4 files are allowed");
        }

        @Test
        @DisplayName("❌ Should fail for filename with invalid characters (control chars)")
        void validate_FilenameWithControlChars_ShouldThrowException() {
            MockMultipartFile file = createValidTestFile("invalid\nname.mp4");
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("Invalid characters in original filename");
        }

        @ParameterizedTest
        @ValueSource(strings = {"../secret.mp4", "C:\\windows\\system.mp4", "/etc/passwd.mp4"})
        @DisplayName("❌ Should fail for filename with path traversal characters")
        void validate_FilenameWithPathTraversal_ShouldThrowException(String filename) {
            MockMultipartFile file = createValidTestFile(filename);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("Invalid characters in original filename");
        }


        @ParameterizedTest
        @ValueSource(strings = {"application/octet-stream", "text/plain", "video/quicktime", "video/x-msvideo"})
        @DisplayName("❌ Should fail for invalid content types")
        void validate_InvalidContentType_ShouldThrowException(String contentType) {
            MockMultipartFile invalidTypeFile = createMockFile("video.mp4", contentType, validMp4MagicBytes);
            assertThatThrownBy(() -> validator.validate(invalidTypeFile))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("Invalid content type detected. Expected video/mp4");
        }

        @Test
        @DisplayName("❌ Should fail for invalid magic bytes")
        void validate_InvalidMagicBytes_ShouldThrowException() {
            MockMultipartFile invalidMagicFile = createMockFile("not_really_mp4.mp4", "video/mp4", invalidMagicBytes);
            assertThatThrownBy(() -> validator.validate(invalidMagicFile))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("File content does not appear to be a valid MP4 video");
        }

        @Test
        @DisplayName("❌ Should fail if file is too short for magic byte check")
        void validate_FileTooShortForMagicBytes_ShouldThrowException() {
            byte[] shortContent = new byte[]{0x00, 0x01, 0x02, 0x03}; // Only 4 bytes
            MockMultipartFile shortFile = createMockFile("short.mp4", "video/mp4", shortContent);
            assertThatThrownBy(() -> validator.validate(shortFile))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("File content does not appear to be a valid MP4 video");
        }

        @Test
        @DisplayName("❌ Should handle IOException during magic byte reading")
        void validate_IOExceptionDuringRead_ShouldThrowException() throws IOException {
            MockMultipartFile mockFile = mock(MockMultipartFile.class);
            when(mockFile.isEmpty()).thenReturn(false);
            when(mockFile.getSize()).thenReturn(1024L); // Valid size
            when(mockFile.getOriginalFilename()).thenReturn("io_error.mp4");
            when(mockFile.getContentType()).thenReturn("video/mp4");
            when(mockFile.getInputStream()).thenThrow(new IOException("Simulated read error"));

            assertThatThrownBy(() -> validator.validate(mockFile))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.INTERNAL_SERVER_ERROR)
                    .hasMessageContaining("Error reading file for validation")
                    .hasCauseInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("❌ Should fail for blank original filename")
        void validate_BlankOriginalFilename_ShouldThrowException() {
            MockMultipartFile file = createMockFile(" ", "video/mp4", validMp4MagicBytes);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("Original file name is missing or blank");
        }

        @Test
        @DisplayName("❌ Should fail for null original filename")
        void validate_NullOriginalFilename_ShouldThrowException() {
            MockMultipartFile file = new MockMultipartFile("file", null, "video/mp4", validMp4MagicBytes);
            assertThatThrownBy(() -> validator.validate(file))
                    .isInstanceOf(VideoValidationException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST)
                    .hasMessageContaining("Original file name is missing or blank");
        }
    }
}
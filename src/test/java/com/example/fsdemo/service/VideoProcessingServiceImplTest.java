package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.web.EditOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir; // Import TempDir for temporary directories
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files; // Import Files
import java.nio.file.Path;  // Import Path
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class) // Initialize mocks
class VideoProcessingServiceImplTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private VideoStorageService videoStorageService;


    private VideoProcessingServiceImpl videoProcessingService;

    // Use JUnit's @TempDir for reliable temporary test directories
    @TempDir
    Path tempTestDir;

    private Path processedStorageLocation;
    private Path temporaryStorageLocation;

    // Test state variables
    private Long videoId;
    private String username;
    private Video video;
    private EditOptions options;
    private Resource mockResource;
    private String originalStoragePath = "original/path/video.mp4";


    @BeforeEach
    void setUp() {
        videoId = 1L;
        username = "testUser";
        options = new EditOptions(null, null, false, 720);

        // Define paths within the temporary directory provided by JUnit
        processedStorageLocation = tempTestDir.resolve("processed");
        temporaryStorageLocation = tempTestDir.resolve("temp");

        // --- Manually instantiate the service ---
        videoProcessingService = new VideoProcessingServiceImpl(
                videoRepository,          // Pass the mock
                videoStorageService,      // Pass the mock
                processedStorageLocation.toString(), // Pass the test path as String
                temporaryStorageLocation.toString()  // Pass the test path as String
        );
        // The constructor will now create these directories inside tempTestDir

        // Setup a base video object
        AppUser owner = new AppUser(username, "pass", "USER", "test@test.com");
        video = new Video(owner, "generated.mp4", "Test", Instant.now(), originalStoragePath, 1024L, "video/mp4");
        video.setId(videoId);
        video.setStatus(VideoStatus.PROCESSING); // Assume controller set this

        // Mock resource for loading
        mockResource = new ByteArrayResource("mock video content".getBytes()) {
            @Override
            public java.io.File getFile() throws IOException {
                throw new UnsupportedOperationException("getFile() not supported in this mock");
            }

            @Override
            public java.net.URI getURI() throws IOException {
                // Use a path consistent with the test setup if needed by service logic
                // For now, let's assume it's not strictly needed for the mocked part
                // If Files.copy uses URI, this might need adjustment
                return Paths.get(originalStoragePath).toUri(); // Example
            }

            @Override
            public java.io.InputStream getInputStream() throws IOException {
                // Ensure the mock resource can provide an InputStream for Files.copy
                return new java.io.ByteArrayInputStream(this.getByteArray());
            }
        };
    }

    // --- Test Cases for processVideoEdits ---

    @Test
    void processVideoEdits_whenSuccess_shouldUpdateStatusToReadyAndSetPath() throws Exception {
        // Arrange
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video)); // Mock both calls
        given(videoStorageService.load(originalStoragePath)).willReturn(mockResource);
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));
        Files.createDirectories(temporaryStorageLocation);

        // Act
        videoProcessingService.processVideoEdits(videoId, options, username);

        // Assert
        then(videoRepository).should(times(2)).findById(videoId);
        then(videoStorageService).should().load(originalStoragePath);

        ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
        then(videoRepository).should(times(1)).save(videoCaptor.capture());

        Video savedVideo = videoCaptor.getValue();
        assertThat(savedVideo.getId()).isEqualTo(videoId);
        assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.READY);
        assertThat(savedVideo.getProcessedStoragePath())
                .isNotNull()
                .startsWith(videoId + "-processed-")
                .endsWith(".mp4");
        assertThat(savedVideo.getStoragePath()).isEqualTo(originalStoragePath);

        assertThat(processedStorageLocation.resolve(savedVideo.getProcessedStoragePath())).exists();
    }

    @Test
    void processVideoEdits_whenProcessingThrowsStorageException_shouldUpdateStatusToFailedAndClearPath() throws Exception {
        // Arrange
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video)); // Mock both potential calls
        given(videoStorageService.load(originalStoragePath))
                .willThrow(new VideoStorageException("Simulated storage error during load"));
        given(videoRepository.save(any(Video.class))).willAnswer(invocation -> invocation.getArgument(0));

        // Act
        videoProcessingService.processVideoEdits(videoId, options, username);

        // Assert
        then(videoRepository).should(times(2)).findById(videoId);
        then(videoStorageService).should().load(originalStoragePath);

        ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
        then(videoRepository).should(times(1)).save(videoCaptor.capture()); // Save called once for FAILED status

        Video savedVideo = videoCaptor.getValue();
        assertThat(savedVideo.getId()).isEqualTo(videoId);
        assertThat(savedVideo.getStatus()).isEqualTo(VideoStatus.FAILED);
        assertThat(savedVideo.getProcessedStoragePath()).isNull();
    }

    @Test
    void processVideoEdits_whenVideoNotFoundDuringExecution_shouldThrowAndNotSave() { // Revert name
        // Arrange
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());

        // Act & Assert
        assertThatThrownBy(() -> videoProcessingService.processVideoEdits(videoId, options, username)) // Expect throw
                .isInstanceOf(IllegalStateException.class) // Check exception type
                .hasMessageContaining("Video not found for processing: " + videoId); // Check message

        // Verify findById was called, but nothing else
        then(videoRepository).should().findById(videoId);
        then(videoStorageService).should(never()).load(anyString());
        then(videoRepository).should(never()).save(any(Video.class));
    }


    @Test
    void processVideoEdits_whenVideoNotInProcessingState_shouldLogWarningAndNotProceed() throws Exception {
        // Arrange
        video.setStatus(VideoStatus.UPLOADED); // Set state to something other than PROCESSING
        given(videoRepository.findById(videoId)).willReturn(Optional.of(video));

        // Act
        videoProcessingService.processVideoEdits(videoId, options, username);

        // Assert
        then(videoRepository).should().findById(videoId); // Should check the video
        // Verify the rest of the process was skipped
        then(videoStorageService).should(never()).load(anyString());
        then(videoRepository).should(never()).save(any(Video.class));
    }
}
package com.example.fsdemo.listeners;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.events.VideoStatusChangedEvent;
import com.example.fsdemo.service.SseService;
import com.example.fsdemo.web.dto.VideoStatusUpdateDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoEventListener Unit Tests")
class VideoEventListenerTest {

    @Mock
    private SseService sseService;

    @InjectMocks
    private VideoEventListener videoEventListener;

    @Captor
    private ArgumentCaptor<VideoStatusUpdateDto> sseDataCaptor;
    @Captor
    private ArgumentCaptor<String> eventNameCaptor;

    private VideoStatusChangedEvent testEvent;
    private final String publicId = "test-public-id";
    private final String username = "testUser";
    private final Video.VideoStatus newStatus = Video.VideoStatus.READY;
    private final String message = "Video is now ready";

    @BeforeEach
    void setUp() {
        testEvent = new VideoStatusChangedEvent(this, publicId, username, newStatus, message);
    }

    @Test
    @DisplayName("✅ handleVideoStatusChange: Should call SseService with correct parameters")
    void handleVideoStatusChange_Success_CallsSseService() {
        doNothing().when(sseService).sendEventToUser(anyString(), any(VideoStatusUpdateDto.class), anyString());

        videoEventListener.handleVideoStatusChange(testEvent);

        verify(sseService).sendEventToUser(eq(username), sseDataCaptor.capture(), eventNameCaptor.capture());

        VideoStatusUpdateDto capturedDto = sseDataCaptor.getValue();
        assertThat(capturedDto.publicId()).isEqualTo(publicId);
        assertThat(capturedDto.status()).isEqualTo(newStatus);
        assertThat(capturedDto.message()).isEqualTo(message);

        assertThat(eventNameCaptor.getValue()).isEqualTo("videoStatusUpdate");
    }

    @Test
    @DisplayName("❌ handleVideoStatusChange: Should catch and log exception from SseService")
    void handleVideoStatusChange_SseServiceThrowsException_ShouldCatchAndLog() {
        doThrow(new RuntimeException("SSE send failed")).when(sseService)
                .sendEventToUser(anyString(), any(VideoStatusUpdateDto.class), anyString());

        assertThatCode(() -> videoEventListener.handleVideoStatusChange(testEvent))
                .doesNotThrowAnyException();

        verify(sseService).sendEventToUser(eq(username), any(VideoStatusUpdateDto.class), eq("videoStatusUpdate"));
    }

    @Test
    @DisplayName("✅ handleVideoStatusChangeRollback: Should log and not call SseService")
    void handleVideoStatusChangeRollback_LogsAndDoesNotCallSseService() {
        videoEventListener.handleVideoStatusChangeRollback(testEvent);

        verifyNoInteractions(sseService);
    }

    @Test
    @DisplayName("❌ VideoStatusChangedEvent constructor: Throws IllegalArgumentException for null publicId")
    void videoStatusChangedEvent_NullPublicId_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new VideoStatusChangedEvent(this, null, username, newStatus, message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Event details (publicId, username, newStatus) cannot be null");
    }

    @Test
    @DisplayName("❌ VideoStatusChangedEvent constructor: Throws IllegalArgumentException for null username")
    void videoStatusChangedEvent_NullUsername_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new VideoStatusChangedEvent(this, publicId, null, newStatus, message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Event details (publicId, username, newStatus) cannot be null");
    }

    @Test
    @DisplayName("❌ VideoStatusChangedEvent constructor: Throws IllegalArgumentException for null newStatus")
    void videoStatusChangedEvent_NullNewStatus_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> new VideoStatusChangedEvent(this, publicId, username, null, message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Event details (publicId, username, newStatus) cannot be null");
    }

    @Test
    @DisplayName("✅ VideoStatusChangedEvent constructor: Allows null message")
    void videoStatusChangedEvent_NullMessage_Allowed() {
        assertThatCode(() -> new VideoStatusChangedEvent(this, publicId, username, newStatus, null))
                .doesNotThrowAnyException();
    }
}
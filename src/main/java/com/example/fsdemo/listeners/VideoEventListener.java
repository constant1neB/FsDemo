package com.example.fsdemo.listeners;

import com.example.fsdemo.events.VideoStatusChangedEvent;
import com.example.fsdemo.service.SseService;
import com.example.fsdemo.web.dto.VideoStatusUpdateDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class VideoEventListener {

    private static final Logger log = LoggerFactory.getLogger(VideoEventListener.class);
    private static final String SSE_EVENT_NAME = "videoStatusUpdate";

    private final SseService sseService;

    public VideoEventListener(SseService sseService) {
        this.sseService = sseService;
    }

    /**
     * Handles the VideoStatusChangedEvent after the transaction has successfully committed.
     * Sends the update via SSE to the relevant user.
     * Marked @Async to avoid blocking the transaction commit thread if SSE sending is slow.
     *
     * @param event The VideoStatusChangedEvent containing details.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleVideoStatusChange(VideoStatusChangedEvent event) {
        VideoStatusUpdateDto sseData = new VideoStatusUpdateDto(
                event.getPublicId(),
                event.getNewStatus(),
                event.getMessage()
        );

        try {
            sseService.sendEventToUser(event.getUsername(), sseData, SSE_EVENT_NAME);
            log.debug("Successfully triggered SSE send for event: [PublicId: {}, User: {}, Status: {}]",
                    event.getPublicId(), event.getUsername(), event.getNewStatus());
        } catch (Exception e) {
            log.error("Unexpected error in VideoEventListener while handling event for user {}: {}",
                    event.getUsername(), e.getMessage(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void handleVideoStatusChangeRollback(VideoStatusChangedEvent event) {
        log.warn("Transaction rolled back for VideoStatusChangedEvent: [PublicId: {}, User: {}, Status: {}]. No SSE sent.",
                event.getPublicId(), event.getUsername(), event.getNewStatus());
    }
}
package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.events.VideoStatusChangedEvent;
import com.example.fsdemo.exceptions.VideoStorageException;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.VideoStatusUpdater;
import com.example.fsdemo.service.VideoStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumSet;

@Service
public class VideoStatusUpdaterImpl implements VideoStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(VideoStatusUpdaterImpl.class);
    private final VideoRepository videoRepository;
    private final VideoStorageService videoStorageService;
    private final ApplicationEventPublisher eventPublisher;

    private static final EnumSet<VideoStatus> ALLOWED_START_PROCESSING_STATUSES =
            EnumSet.of(VideoStatus.UPLOADED, VideoStatus.READY, VideoStatus.FAILED);

    @Autowired
    public VideoStatusUpdaterImpl(VideoRepository videoRepository,
                                  VideoStorageService videoStorageService,
                                  ApplicationEventPublisher eventPublisher) {
        this.videoRepository = videoRepository;
        this.videoStorageService = videoStorageService;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusToProcessing(Long videoId) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        log.info("[StatusUpdater][TX:{}] Attempting to set status to PROCESSING for video ID: {}", txName, videoId);

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> {
                    log.error("[StatusUpdater][TX:{}] Video not found for status update to PROCESSING: {}", txName, videoId);
                    return new IllegalStateException("Video not found: " + videoId);
                });

        AppUser owner = video.getOwner();
        String username = (owner != null) ? owner.getUsername() : null;
        String publicId = video.getPublicId();

        if (video.getStatus() == VideoStatus.PROCESSING) {
            log.warn("[StatusUpdater][TX:{}] Video {} is already PROCESSING. No status change needed.", txName, videoId);
            publishEvent(publicId, username, VideoStatus.PROCESSING, null);
            return;
        }

        if (!ALLOWED_START_PROCESSING_STATUSES.contains(video.getStatus())) {
            log.warn("[StatusUpdater][TX:{}] Video {} cannot transition to PROCESSING from state {}.",
                    txName, videoId, video.getStatus());
            throw new IllegalStateException("Video cannot be processed in its current state: " + video.getStatus());
        }

        cleanupPreviousProcessedFile(video, txName);

        try {
            video.setStatus(VideoStatus.PROCESSING);
            video.setProcessedStoragePath(null);
            videoRepository.save(video);
            log.info("[StatusUpdater][TX:{}] Successfully set status to PROCESSING and cleared processed path for video ID: {}",
                    txName, videoId);
            publishEvent(publicId, username, VideoStatus.PROCESSING, null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update video status to PROCESSING for video ID: " + videoId, e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusToReady(Long videoId, String processedStoragePath) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        log.info("[StatusUpdater][TX:{}] Attempting to set status to READY for video ID: {} with path: {}",
                txName, videoId, processedStoragePath);

        if (processedStoragePath == null || processedStoragePath.isBlank()) {
            log.error("[StatusUpdater][TX:{}] Processed storage path cannot be null or blank when setting status to READY for video ID: {}",
                    txName, videoId);
            throw new IllegalArgumentException("Processed storage path is required to set status to READY.");
        }

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> {
                    log.error("[StatusUpdater][TX:{}] Video not found for status update to READY: {}", txName, videoId);
                    return new IllegalStateException("Video not found: " + videoId);
                });

        AppUser owner = video.getOwner();
        String username = (owner != null) ? owner.getUsername() : null;
        String publicId = video.getPublicId();

        if (video.getStatus() != VideoStatus.PROCESSING) {
            log.warn("[StatusUpdater][TX:{}] Video {} cannot transition to READY from state {} (Expected PROCESSING). Possible race condition or error.",
                    txName, videoId, video.getStatus());
        }

        try {
            video.setStatus(VideoStatus.READY);
            video.setProcessedStoragePath(processedStoragePath);
            videoRepository.save(video);
            log.info("[StatusUpdater][TX:{}] Successfully set status to READY for video ID: {}", txName, videoId);
            publishEvent(publicId, username, VideoStatus.READY, null);
        } catch (Exception e) {
            log.error("[StatusUpdater][TX:{}] CRITICAL: Failed to update video status/path to READY in database for video ID: {}",
                    txName, videoId, e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusToFailed(Long videoId) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        log.info("[StatusUpdater][TX:{}] Attempting to set status to FAILED for video ID: {}", txName, videoId);
        try {
            Video videoToFail = videoRepository.findById(videoId).orElse(null);
            if (videoToFail != null) {
                AppUser owner = videoToFail.getOwner();
                String username = (owner != null) ? owner.getUsername() : null;
                String publicId = videoToFail.getPublicId();

                if (videoToFail.getStatus() == VideoStatus.PROCESSING) {
                    videoToFail.setStatus(VideoStatus.FAILED);
                    videoToFail.setProcessedStoragePath(null);
                    videoRepository.save(videoToFail);
                    log.info("[StatusUpdater][TX:{}] Successfully set status to FAILED for video ID: {}",
                            txName, videoId);
                    publishEvent(publicId, username, VideoStatus.FAILED, "Video processing failed.");
                } else {
                    log.warn("[StatusUpdater][TX:{}] Video {} status was not PROCESSING during failure handling (actual: {}). Not modifying status.",
                            txName, videoId, videoToFail.getStatus());
                }
            } else {
                log.error("[StatusUpdater][TX:{}] Video {} not found during failure status update.", txName, videoId);
            }
        } catch (Exception updateEx) {
            log.error("[StatusUpdater][TX:{}] CRITICAL: Failed to update status to FAILED for video ID: {}",
                    txName, videoId, updateEx);
        }
    }

    // Helper methods

    /**
     * Publishes a VideoStatusChangedEvent if username and publicId are available.
     */
    private void publishEvent(String publicId, String username, Video.VideoStatus status, String message) {
        if (username != null && publicId != null) {
            VideoStatusChangedEvent event = new VideoStatusChangedEvent(this, publicId, username, status, message);
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.error("Failed to publish VideoStatusChangedEvent [PublicId: {}, User: {}, Status: {}]: {}",
                        publicId, username, status, e.getMessage(), e);
            }
        } else {
            log.error("Cannot publish VideoStatusChangedEvent: Username ({}) or PublicId ({}) is null.", username, publicId);
        }
    }

    /**
     * Helper to clean up previously processed files if they exist.
     *
     * @param video  The video entity.
     * @param txName The current transaction name for logging.
     */
    private void cleanupPreviousProcessedFile(Video video, String txName) {
        String existingProcessedPath = video.getProcessedStoragePath();
        if (existingProcessedPath != null && !existingProcessedPath.isBlank()) {
            log.info("[StatusUpdater][TX:{}] Video {} (Status: {}) has existing processed path '{}'. Attempting cleanup before processing.",
                    txName, video.getId(), video.getStatus(), existingProcessedPath);
            try {
                videoStorageService.delete(existingProcessedPath);
            } catch (VideoStorageException e) {
                log.warn("[StatusUpdater][TX:{}] Failed to delete previous processed file '{}' for video {}. Processing will proceed. Reason: {}",
                        txName, existingProcessedPath, video.getId(), e.getMessage());
            } catch (Exception e) {
                log.error("[StatusUpdater][TX:{}] Unexpected error deleting previous processed file '{}' for video {}. Processing will proceed.",
                        txName, existingProcessedPath, video.getId(), e);
            }
        }
    }
}
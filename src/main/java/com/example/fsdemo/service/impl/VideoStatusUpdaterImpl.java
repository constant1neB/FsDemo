package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.Video.VideoStatus;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.VideoStatusUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.EnumSet;

@Service
public class VideoStatusUpdaterImpl implements VideoStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(VideoStatusUpdaterImpl.class);
    private final VideoRepository videoRepository;

    private static final EnumSet<VideoStatus> ALLOWED_START_PROCESSING_STATUSES =
            EnumSet.of(VideoStatus.UPLOADED, VideoStatus.READY);

    @Autowired
    public VideoStatusUpdaterImpl(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
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

        if (!ALLOWED_START_PROCESSING_STATUSES.contains(video.getStatus())) {
            log.warn("[StatusUpdater][TX:{}] Video {} cannot transition to PROCESSING from state {}.",
                    txName, videoId, video.getStatus());
            throw new IllegalStateException("Video cannot be processed in its current state: " + video.getStatus());
        }

        if (video.getStatus() == VideoStatus.PROCESSING) {
            log.warn("[StatusUpdater][TX:{}] Video {} is already PROCESSING. No status change needed.", txName, videoId);
            return;
        }

        try {
            video.setStatus(VideoStatus.PROCESSING);
            video.setProcessedStoragePath(null); // Clear any previous processed path
            videoRepository.save(video);
            log.info("[StatusUpdater][TX:{}] Successfully set status to PROCESSING for video ID: {}", txName, videoId);
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
            log.error("[StatusUpdater][TX:{}] Processed storage path cannot be null or blank when setting status to READY for video ID: {}", txName, videoId);
            throw new IllegalArgumentException("Processed storage path is required to set status to READY.");
        }

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> {
                    log.error("[StatusUpdater][TX:{}] Video not found for status update to READY: {}", txName, videoId);
                    // This shouldn't happen if processing just finished, but handle defensively.
                    return new IllegalStateException("Video not found: " + videoId);
                });

        if (video.getStatus() != VideoStatus.PROCESSING) {
            log.warn("[StatusUpdater][TX:{}] Video {} cannot transition to READY from state {} (Expected PROCESSING).",
                    txName, videoId, video.getStatus());
            // This might indicate a race condition or logic error if processing completed but status wasn't PROCESSING.
            throw new IllegalStateException("Video is not in PROCESSING state, cannot set to READY. Current state: " +
                    video.getStatus());
        }

        try {
            video.setStatus(VideoStatus.READY);
            video.setProcessedStoragePath(processedStoragePath);
            // Potentially set other metadata like duration here if extracted during processing
            videoRepository.save(video);
            log.info("[StatusUpdater][TX:{}] Successfully set status to READY for video ID: {}", txName, videoId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update video status to READY for video ID: " + videoId, e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatusToFailed(Long videoId) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        log.info("[StatusUpdater][TX:{}] Attempting to set status to FAILED for video ID: {}", txName, videoId);
        try {
            // Find the video within this new transaction
            Video videoToFail = videoRepository.findById(videoId).orElse(null);
            if (videoToFail != null) {
                // Check if the status needs updating (idempotency) - Only fail from PROCESSING
                if (videoToFail.getStatus() == VideoStatus.PROCESSING) {
                    videoToFail.setStatus(VideoStatus.FAILED);
                    videoToFail.setProcessedStoragePath(null); // Ensure no processed path is associated on failure
                    videoRepository.save(videoToFail); // Persist the FAILED status
                    log.info("[StatusUpdater][TX:{}] Successfully set status to FAILED for video ID: {}", txName, videoId);
                } else {
                    log.warn("[StatusUpdater][TX:{}] Video {} status was not PROCESSING during failure handling (actual: {}). Not modifying status.",
                            txName, videoId, videoToFail.getStatus());
                }
            } else {
                log.error("[StatusUpdater][TX:{}] Video {} not found during failure status update.", txName, videoId);
            }
        } catch (Exception updateEx) {
            // Log critical failure: The attempt to mark the video as FAILED in the DB itself failed.
            log.error("[StatusUpdater][TX:{}] CRITICAL: Failed to update status to FAILED for video ID: {}", txName, videoId, updateEx);
            // Do NOT re-throw here. The goal is to log this specific failure,
            // but let the original exception that triggered this call propagate.
        }
    }
}
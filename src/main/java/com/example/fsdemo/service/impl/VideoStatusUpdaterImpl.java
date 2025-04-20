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

@Service
public class VideoStatusUpdaterImpl implements VideoStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(VideoStatusUpdaterImpl.class);
    private final VideoRepository videoRepository;

    @Autowired
    public VideoStatusUpdaterImpl(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW) // Ensure this runs in its own transaction
    public void updateStatusToFailed(Long videoId) {
        String txName = TransactionSynchronizationManager.getCurrentTransactionName();
        log.info("[VideoStatusUpdater][TX:{}] Attempting to set status to FAILED for video ID: {}", txName, videoId);
        try {
            // Find the video within this new transaction
            Video videoToFail = videoRepository.findById(videoId).orElse(null);
            if (videoToFail != null) {
                // Check if the status needs updating (idempotency)
                if (videoToFail.getStatus() == VideoStatus.PROCESSING) {
                    videoToFail.setStatus(VideoStatus.FAILED);
                    videoToFail.setProcessedStoragePath(null); // Ensure no processed path is associated on failure
                    videoRepository.save(videoToFail); // Persist the FAILED status
                    log.info("[VideoStatusUpdater][TX:{}] Successfully set status to FAILED for video ID: {}", txName, videoId);
                } else {
                    log.warn("[VideoStatusUpdater][TX:{}] Video {} status was not PROCESSING during failure handling (actual: {}). Not modifying status.",
                            txName, videoId, videoToFail.getStatus());
                }
            } else {
                log.error("[VideoStatusUpdater][TX:{}] Video {} not found during failure status update.", txName, videoId);
            }
        } catch (Exception updateEx) {
            // Log critical failure: The attempt to mark the video as FAILED in the DB itself failed.
            log.error("[VideoStatusUpdater][TX:{}] CRITICAL: Failed to update status to FAILED for video ID: {}", txName, videoId, updateEx);
            // This situation might require manual intervention or monitoring.
        }
    }
}
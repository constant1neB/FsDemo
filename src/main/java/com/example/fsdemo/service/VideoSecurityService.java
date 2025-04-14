package com.example.fsdemo.service;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class VideoSecurityService {

    private static final Logger log = LoggerFactory.getLogger(VideoSecurityService.class);
    private final VideoRepository videoRepository;

    public VideoSecurityService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    /**
     * Checks if the given username is the owner of the video.
     *
     * @param videoId  The ID of the video.
     * @param username The username to check.
     * @return true if the user is the owner, false otherwise.
     */
    public boolean isOwner(Long videoId, String username) {
        if (username == null || videoId == null) {
            return false;
        }
        Optional<Video> videoOpt = videoRepository.findById(videoId);
        boolean owner = videoOpt.map(video -> video.getOwner().getUsername().equals(username))
                .orElse(false);
        log.trace("isOwner check for videoId: {}, username: {}. Result: {}", videoId, username, owner);
        return owner;
    }

    /**
     * Checks if the given user can view the video (is owner or video is public).
     *
     * @param videoId  The ID of the video.
     * @param username The username attempting to view.
     * @return true if viewing is allowed, false otherwise.
     */
    public boolean canView(Long videoId, String username) {
        if (username == null || videoId == null) {
            return false;
        }
        Optional<Video> videoOpt = videoRepository.findById(videoId);

        // If video doesn't exist, cannot view
        if (videoOpt.isEmpty()) {
            log.trace("canView check failed for videoId: {}. Video not found.", videoId);
            return false;
        }

        Video video = videoOpt.get();

        // Check if owner OR if public
        boolean allowed = video.getOwner().getUsername().equals(username) || video.isPublic();
        log.trace("canView check for videoId: {}, username: {}. Owner: {}, Public: {}. Result: {}",
                videoId, username, video.getOwner().getUsername().equals(username), video.isPublic(), allowed);
        return allowed;
    }

    /**
     * Checks if the given user can delete the video (typically, only the owner).
     * Currently delegates to isOwner.
     *
     * @param videoId  The ID of the video.
     * @param username The username attempting to delete.
     * @return true if deletion is allowed, false otherwise.
     */
    public boolean canDelete(Long videoId, String username) {
        // For now, deletion requires ownership
        boolean allowed = isOwner(videoId, username);
        log.trace("canDelete check for videoId: {}, username: {}. Result: {}", videoId, username, allowed);
        return allowed;
    }

    // Removed helper 'checkIsPublic' and 'canViewAlternative' as the direct logic in canView is clear enough.
}

package com.example.fsdemo.service;

import com.example.fsdemo.domain.Video; // Import Video entity
import com.example.fsdemo.domain.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional; // Import Optional

@Service
@Transactional(readOnly = true)
public class VideoSecurityService {
    private final VideoRepository videoRepository;

    public VideoSecurityService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    public boolean isOwner(Long videoId, String username) {
        return videoRepository.findById(videoId)
                .map(video -> video.getOwner().getUsername().equals(username))
                .orElse(false);
    }

    public boolean canView(Long videoId, String username) {
        // *** MODIFY THIS LOGIC ***
        Optional<Video> videoOpt = videoRepository.findById(videoId);

        // Check if the video exists and if the user is the owner OR if the video is public
        return videoOpt.map(video ->
                video.getOwner().getUsername().equals(username) || video.isPublic()
        ).orElse(false); // If video doesn't exist, user cannot view it
    }

    // Helper method to get the isPublic flag safely (handles video not found)
    private boolean checkIsPublic(Long videoId) {
        return videoRepository.findById(videoId)
                .map(Video::isPublic) // Use method reference
                .orElse(false); // Treat non-existent video as not public
    }

    // Alternative canView implementation using the helper
    public boolean canViewAlternative(Long videoId, String username) {
        return isOwner(videoId, username) || checkIsPublic(videoId);
    }
}

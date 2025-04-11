package com.example.fsdemo.service;

import com.example.fsdemo.domain.VideoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return isOwner(videoId, username) ||
                videoRepository.isPublic(videoId);
    }
}

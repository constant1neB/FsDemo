package com.example.fsdemo.service.impl;

import com.example.fsdemo.domain.Video;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.VideoSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class VideoSecurityServiceImpl implements VideoSecurityService {

    private static final Logger log = LoggerFactory.getLogger(VideoSecurityServiceImpl.class);
    private final VideoRepository videoRepository;

    public VideoSecurityServiceImpl(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    @Override
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

    @Override
    public boolean canView(Long videoId, String username) {
        boolean allowed = isOwner(videoId, username);
        log.trace("canView check (private only) for videoId: {}, username: {}. Result: {}",
                videoId, username, allowed);
        return allowed;
    }

    @Override
    public boolean canDelete(Long videoId, String username) {
        boolean allowed = isOwner(videoId, username);
        log.trace("canDelete check for videoId: {}, username: {}. Result: {}", videoId, username, allowed);
        return allowed;
    }
}
package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.impl.VideoSecurityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VideoSecurityServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private VideoSecurityServiceImpl videoSecurityService;

    private AppUser ownerUser;
    private AppUser otherUser;
    private Video userVideo;
    private final Long videoId = 1L;
    private final Long nonExistentVideoId = 99L;

    @BeforeEach
    void setUp() {
        ownerUser = new AppUser("owner", "pass", "USER", "owner@example.com");
        ownerUser.setId(10L);

        otherUser = new AppUser("other", "pass", "USER", "other@example.com");
        otherUser.setId(20L);


        userVideo = new Video(ownerUser, "user-video.mp4", "desc", Instant.now(), "path/user-video.mp4", 100L, "video/mp4");
        userVideo.setId(videoId);
    }

    // --- isOwner Tests ---

    @Test
    void isOwner_whenUserIsOwner_shouldReturnTrue() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.isOwner(videoId, ownerUser.getUsername());
        assertThat(result).isTrue();
        verify(videoRepository).findById(videoId); // Example verification
    }

    @Test
    void isOwner_whenUserIsNotOwner_shouldReturnFalse() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.isOwner(videoId, otherUser.getUsername());
        assertThat(result).isFalse();
        verify(videoRepository).findById(videoId);
    }

    @Test
    void isOwner_whenVideoNotFound_shouldReturnFalse() {
        when(videoRepository.findById(nonExistentVideoId)).thenReturn(Optional.empty());
        boolean result = videoSecurityService.isOwner(nonExistentVideoId, ownerUser.getUsername());
        assertThat(result).isFalse();
        verify(videoRepository).findById(nonExistentVideoId);
    }


    @Test
    void canView_whenUserIsOwner_shouldReturnTrue() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canView(videoId, ownerUser.getUsername());
        assertThat(result).isTrue();
        verify(videoRepository).findById(videoId); // Verify findById was called (via isOwner)
    }

    @Test
    void canView_whenUserIsNotOwner_shouldReturnFalse() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canView(videoId, otherUser.getUsername());
        assertThat(result).isFalse();
        verify(videoRepository).findById(videoId); // Verify findById was called (via isOwner)
    }

    @Test
    void canView_whenVideoNotFound_shouldReturnFalse() {
        when(videoRepository.findById(nonExistentVideoId)).thenReturn(Optional.empty());
        boolean result = videoSecurityService.canView(nonExistentVideoId, ownerUser.getUsername());
        assertThat(result).isFalse();
        verify(videoRepository).findById(nonExistentVideoId); // Verify findById was called (via isOwner)
    }

    @Test
    void canDelete_whenUserIsOwner_shouldReturnTrue() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canDelete(videoId, ownerUser.getUsername());
        assertThat(result).isTrue();
        verify(videoRepository).findById(videoId);
    }

    @Test
    void canDelete_whenUserIsNotOwner_shouldReturnFalse() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canDelete(videoId, otherUser.getUsername());
        assertThat(result).isFalse();
        verify(videoRepository).findById(videoId);
    }

    @Test
    void canDelete_whenVideoNotFound_shouldReturnFalse() {
        when(videoRepository.findById(nonExistentVideoId)).thenReturn(Optional.empty());
        boolean result = videoSecurityService.canDelete(nonExistentVideoId, ownerUser.getUsername());
        assertThat(result).isFalse();
        verify(videoRepository).findById(nonExistentVideoId);
    }
}
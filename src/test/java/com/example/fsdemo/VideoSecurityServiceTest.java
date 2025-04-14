package com.example.fsdemo;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.domain.VideoRepository;
import com.example.fsdemo.service.VideoSecurityService;
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

@ExtendWith(MockitoExtension.class) // Use MockitoExtension for automatic mock initialization
class VideoSecurityServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks // Automatically injects mocks into this instance
    private VideoSecurityService videoSecurityService;

    private AppUser ownerUser;
    private AppUser otherUser;
    private Video privateVideo;
    private Video publicVideo;
    private final Long videoId = 1L;
    private final Long nonExistentVideoId = 99L;

    @BeforeEach
    void setUp() {
        ownerUser = new AppUser("owner", "pass", "USER", "owner@example.com");
        ownerUser.setId(10L); // Set ID for clarity

        otherUser = new AppUser("other", "pass", "USER", "other@example.com");
        otherUser.setId(20L);

        privateVideo = new Video(ownerUser, "private.mp4", "desc", Instant.now(), "path/private.mp4", 100L, "video/mp4");
        privateVideo.setId(videoId);
        privateVideo.setPublic(false); // Explicitly private

        publicVideo = new Video(ownerUser, "public.mp4", "desc", Instant.now(), "path/public.mp4", 100L, "video/mp4");
        publicVideo.setId(videoId + 1); // Different ID
        publicVideo.setPublic(true); // Explicitly public
    }

    // --- isOwner Tests ---

    @Test
    void isOwner_whenUserIsOwner_shouldReturnTrue() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(privateVideo));
        boolean result = videoSecurityService.isOwner(videoId, ownerUser.getUsername());
        assertThat(result).isTrue();
    }

    @Test
    void isOwner_whenUserIsNotOwner_shouldReturnFalse() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(privateVideo));
        boolean result = videoSecurityService.isOwner(videoId, otherUser.getUsername());
        assertThat(result).isFalse();
    }

    @Test
    void isOwner_whenVideoNotFound_shouldReturnFalse() {
        when(videoRepository.findById(nonExistentVideoId)).thenReturn(Optional.empty());
        boolean result = videoSecurityService.isOwner(nonExistentVideoId, ownerUser.getUsername());
        assertThat(result).isFalse();
    }

    // --- canView Tests --- (Using alternative implementation logic)

    @Test
    void canView_whenUserIsOwner_shouldReturnTrue() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(privateVideo)); // Owner check is enough
        boolean result = videoSecurityService.canView(videoId, ownerUser.getUsername());
        assertThat(result).isTrue();
    }

    @Test
    void canView_whenUserIsNotOwnerAndVideoIsPublic_shouldReturnTrue() {
        when(videoRepository.findById(publicVideo.getId())).thenReturn(Optional.of(publicVideo));
        boolean result = videoSecurityService.canView(publicVideo.getId(), otherUser.getUsername());
        assertThat(result).isTrue();
    }

    @Test
    void canView_whenUserIsNotOwnerAndVideoIsPrivate_shouldReturnFalse() {
        when(videoRepository.findById(privateVideo.getId())).thenReturn(Optional.of(privateVideo));
        boolean result = videoSecurityService.canView(privateVideo.getId(), otherUser.getUsername());
        assertThat(result).isFalse();
    }

    @Test
    void canView_whenVideoNotFound_shouldReturnFalse() {
        when(videoRepository.findById(nonExistentVideoId)).thenReturn(Optional.empty());
        boolean result = videoSecurityService.canView(nonExistentVideoId, ownerUser.getUsername());
        assertThat(result).isFalse();
    }

    // --- canDelete Tests (Assuming same logic as isOwner) ---

    @Test
    void canDelete_whenUserIsOwner_shouldReturnTrue() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(privateVideo));
        boolean result = videoSecurityService.canDelete(videoId, ownerUser.getUsername());
        assertThat(result).isTrue();
    }

    @Test
    void canDelete_whenUserIsNotOwner_shouldReturnFalse() {
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(privateVideo));
        boolean result = videoSecurityService.canDelete(videoId, otherUser.getUsername());
        assertThat(result).isFalse();
    }

    @Test
    void canDelete_whenVideoNotFound_shouldReturnFalse() {
        when(videoRepository.findById(nonExistentVideoId)).thenReturn(Optional.empty());
        boolean result = videoSecurityService.canDelete(nonExistentVideoId, ownerUser.getUsername());
        assertThat(result).isFalse();
    }
}

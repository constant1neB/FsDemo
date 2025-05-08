package com.example.fsdemo.service;

import com.example.fsdemo.domain.AppUser;
import com.example.fsdemo.domain.Video;
import com.example.fsdemo.repository.VideoRepository;
import com.example.fsdemo.service.impl.VideoSecurityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoSecurityService Implementation Tests")
class VideoSecurityServiceImplTest {

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private VideoSecurityServiceImpl videoSecurityService;

    private Video userVideo;
    private final Long videoId = 1L;
    private final String ownerUsername = "ownerUser";
    private final String otherUsername = "otherUser";

    @BeforeEach
    void setUp() {
        AppUser ownerUser = new AppUser(ownerUsername, "pass", "USER", "owner@example.com");
        ownerUser.setId(10L);
        userVideo = new Video(ownerUser, "Video Description", Instant.now(), "path/user-video.mp4", 100L, "video/mp4");
        ReflectionTestUtils.setField(userVideo, "id", videoId);
    }

    @Test
    @DisplayName("✅ isOwner: Should return true if username matches video owner")
    void isOwner_TrueForOwner() {
        given(videoRepository.findById(videoId)).willReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.isOwner(videoId, ownerUsername);
        assertThat(result).isTrue();
        then(videoRepository).should().findById(videoId);
    }

    @Test
    @DisplayName("❌ isOwner: Should return false if username does not match video owner")
    void isOwner_FalseForNonOwner() {
        given(videoRepository.findById(videoId)).willReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.isOwner(videoId, otherUsername);
        assertThat(result).isFalse();
        then(videoRepository).should().findById(videoId);
    }

    @Test
    @DisplayName("❌ isOwner: Should return false if video not found")
    void isOwner_FalseVideoNotFound() {
        given(videoRepository.findById(videoId)).willReturn(Optional.empty());
        boolean result = videoSecurityService.isOwner(videoId, ownerUsername);
        assertThat(result).isFalse();
        then(videoRepository).should().findById(videoId);
    }

    @Test
    @DisplayName("❌ isOwner: Should return false for null username")
    void isOwner_FalseNullUsername() {
        boolean result = videoSecurityService.isOwner(videoId, null);
        assertThat(result).isFalse();
        then(videoRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("❌ isOwner: Should return false for null videoId")
    void isOwner_FalseNullVideoId() {
        boolean result = videoSecurityService.isOwner(null, ownerUsername);
        assertThat(result).isFalse();
        then(videoRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("✅ canView: Should return true if user is owner")
    void canView_TrueForOwner() {
        given(videoRepository.findById(videoId)).willReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canView(videoId, ownerUsername);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("❌ canView: Should return false if user is not owner")
    void canView_FalseForNonOwner() {
        given(videoRepository.findById(videoId)).willReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canView(videoId, otherUsername);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("✅ canDelete: Should return true if user is owner")
    void canDelete_TrueForOwner() {
        given(videoRepository.findById(videoId)).willReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canDelete(videoId, ownerUsername);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("❌ canDelete: Should return false if user is not owner")
    void canDelete_FalseForNonOwner() {
        given(videoRepository.findById(videoId)).willReturn(Optional.of(userVideo));
        boolean result = videoSecurityService.canDelete(videoId, otherUsername);
        assertThat(result).isFalse();
    }
}
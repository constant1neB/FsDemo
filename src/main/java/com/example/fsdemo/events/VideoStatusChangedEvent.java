package com.example.fsdemo.events;

import com.example.fsdemo.domain.Video;
import org.springframework.context.ApplicationEvent;

/**
 * Event published when a Video's status is successfully updated in the database.
 */
public class VideoStatusChangedEvent extends ApplicationEvent {

    private final String publicId;
    private final String username;
    private final Video.VideoStatus newStatus;
    private final String message;

    /**
     * Create a new VideoStatusChangedEvent.
     *
     * @param source    The component that published the event (usually 'this').
     * @param publicId  The public ID of the video.
     * @param username  The username of the video owner.
     * @param newStatus The new status of the video.
     * @param message   An optional message associated with the status change.
     */
    public VideoStatusChangedEvent(Object source, String publicId, String username, Video.VideoStatus newStatus, String message) {
        super(source);
        if (publicId == null || username == null || newStatus == null) {
            throw new IllegalArgumentException("Event details (publicId, username, newStatus) cannot be null");
        }
        this.publicId = publicId;
        this.username = username;
        this.newStatus = newStatus;
        this.message = message;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getUsername() {
        return username;
    }

    public Video.VideoStatus getNewStatus() {
        return newStatus;
    }

    public String getMessage() {
        return message;
    }
}
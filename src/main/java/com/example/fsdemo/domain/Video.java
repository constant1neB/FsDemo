package com.example.fsdemo.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(indexes = {
        @Index(name = "idx_video_owner", columnList = "user_id"),
        @Index(name = "idx_video_path", columnList = "storagePath", unique = true)
})
public class Video {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use IDENTITY for auto-increment PKs
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) // LAZY is good practice
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, length = 255) // Max filename length
    private String originalFilename;

    @Column(length = 1024) // Allow longer descriptions
    private String description;

    @Column(nullable = false)
    private Instant uploadDate; // Use Instant for timestamp

    @Column(nullable = false, unique = true) // Path must be unique
    private String storagePath;

    @Column(nullable = false)
    private boolean isPublic = false; // Default to private

    @Column(nullable = false) // Required by the test/controller
    private Long fileSize;

    private String mimeType;
    private Double duration;

    @Enumerated(EnumType.STRING)
    private VideoStatus status = VideoStatus.UPLOADED; // Default status after upload


    public enum VideoStatus {UPLOADED, PROCESSING, READY, FAILED}


    public Video() {
    }

    public Video(AppUser owner, String originalFilename, String description, Instant uploadDate, String storagePath, Long fileSize) {
        this.owner = owner;
        this.originalFilename = originalFilename;
        this.description = description;
        this.uploadDate = uploadDate;
        this.storagePath = storagePath;
        this.fileSize = fileSize;
    }

    public Long getId() {
        return id;
    }

    public AppUser getOwner() {
        return owner;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getDescription() {
        return description;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getStoragePath() {
        return storagePath;
    } // Added getter

    public void setId(Long id) {
        this.id = id;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setUploadDate(Instant uploadDate) {
        this.uploadDate = uploadDate;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean aPublic) {
        isPublic = aPublic;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public void setDuration(Double duration) {
        this.duration = duration;
    }

    public void setStatus(VideoStatus status) {
        this.status = status;
    }
}
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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser owner;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(length = 1024)
    private String description;

    @Column(nullable = false)
    private Instant uploadDate; // Changed from LocalDateTime

    @Column(nullable = false, unique = true)
    private String storagePath;

    @Column(nullable = false)
    private boolean isPublic = false;

    private String mimeType; // New
    private Long fileSize;
    private Double duration;

    @Enumerated(EnumType.STRING)
    private VideoStatus status = VideoStatus.ORIGINAL;

    public enum VideoStatus {ORIGINAL, PROCESSING, PROCESSED, FAILED}

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

    public void setId(Long id) {
        this.id = id;
    }

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public void setOriginalFilename(String originalFilename) {
        this.originalFilename = originalFilename;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getUploadDate() {
        return uploadDate;
    }

    public void setUploadDate(Instant uploadDate) {
        this.uploadDate = uploadDate;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Double getDuration() {
        return duration;
    }

    public void setDuration(Double duration) {
        this.duration = duration;
    }

}

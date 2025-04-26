package com.example.fsdemo.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "videos",
        indexes = {
                @Index(name = "idx_video_owner", columnList = "user_id"),
                @Index(name = "idx_video_public_id", columnList = "publicId", unique = true),
                @Index(name = "idx_video_storage_path", columnList = "storagePath", unique = true),
                @Index(name = "idx_video_processed_path", columnList = "processedStoragePath", unique = true)
        })
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Public identifier for external use (API, frontend)
    @Column(nullable = false, unique = true, updatable = false, length = 36)
    private String publicId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_video_owner"))
    private AppUser owner;

    @Pattern(regexp = "^[\\p{L}0-9.,!?_;:() \\r\\n-]*$",
            message = "Description contains invalid characters. Only letters, numbers, whitespace (including newlines), and basic punctuation (. , ! ? - _ ; : ( )) are allowed.")
    @Size(max = 255, message = "Description cannot exceed 255 characters")
    @Column()
    private String description;

    @Column(nullable = false)
    private Instant uploadDate;

    // Path where the file is actually stored (can be just the generatedFilename or a longer path)
    @Column(nullable = false, unique = true, length = 512) // Increased length for flexibility
    private String storagePath;

    // Path where the latest processed file is stored (nullable if not processed yet or failed)
    @Column(unique = true, length = 512) // Nullable, unique path for processed file
    private String processedStoragePath;

    @Column(nullable = false)
    private Long fileSize; // Store file size in bytes

    @Column(length = 50) // e.g., "video/mp4"
    private String mimeType;

    @Column // Nullable until processed
    private Double duration; // Duration in seconds, to be set after processing

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private VideoStatus status = VideoStatus.UPLOADED;

    @Version
    private Long version;

    public enum VideoStatus {
        UPLOADED, // Initial state after successful upload and metadata save
        PROCESSING, // Video editing/transcoding is in progress
        READY, // Processing complete, video is ready for viewing/download
        FAILED // Processing failed
    }

    public Video() {
    }

    // Constructor for creating new Video instances (e.g., in Controller)
    public Video(AppUser owner, String description, Instant uploadDate, String storagePath, Long fileSize, String mimeType) {
        this.publicId = UUID.randomUUID().toString();
        this.owner = owner;
        this.description = description;
        this.uploadDate = uploadDate;
        this.storagePath = storagePath;
        this.fileSize = fileSize;
        this.mimeType = mimeType;
        // Status defaults to UPLOADED via field initializer
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPublicId() {
        return publicId;
    }

    public AppUser getOwner() {
        return owner;
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

    public String getStoragePath() {
        return storagePath;
    }

    public String getProcessedStoragePath() {
        return processedStoragePath;
    }

    public void setProcessedStoragePath(String processedStoragePath) {
        this.processedStoragePath = processedStoragePath;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public Double getDuration() {
        return duration;
    }

    public void setDuration(Double duration) {
        this.duration = duration;
    }

    public VideoStatus getStatus() {
        return status;
    }

    public void setStatus(VideoStatus status) {
        this.status = status;
    }

    // Consider adding equals() and hashCode() based on ID if needed
    // Consider adding toString() for logging purposes
}
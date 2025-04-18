package com.example.fsdemo.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

@Entity
@Table(name = "videos", // Explicit table name is good practice
        indexes = {
                @Index(name = "idx_video_owner", columnList = "user_id"),
                // Index on storagePath might still be useful, even if it's opaque
                @Index(name = "idx_video_storage_path", columnList = "storagePath", unique = true),
                @Index(name = "idx_video_processed_path", columnList = "processedStoragePath", unique = true)
                // Add index on the generated filename if we query by it often (probably not needed initially)
                // @Index(name = "idx_video_generated_filename", columnList = "generatedFilename")
        })
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use IDENTITY for auto-increment PKs
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) // LAZY is good practice for performance
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_video_owner"))
    private AppUser owner;

    // Server-generated UUID-based name.
    @Column(nullable = false, length = 50) // UUID (36 chars) + .mp4 (4 chars) = 40. 50 gives buffer.
    private String generatedFilename;

    @Pattern(regexp = "^[\\p{L}0-9.,!?_;:() \\r\\n-]*$",
            message = "Description contains invalid characters. Only letters, numbers, whitespace (including newlines), and basic punctuation (. , ! ? - _ ; : ( )) are allowed.")
    @Size(max = 255, message = "Description cannot exceed 255 characters")
    @Column()
    private String description;

    @Column(nullable = false)
    private Instant uploadDate; // Use Instant for timestamp

    // Path where the file is actually stored (can be just the generatedFilename or a longer path)
    @Column(nullable = false, unique = true, length = 512) // Increased length for flexibility
    private String storagePath;

    // Path where the LATEST PROCESSED file is stored (nullable if not processed yet or failed)
    @Column(unique = true, length = 512) // Nullable, unique path for processed file
    private String processedStoragePath;

    @Column(nullable = false)
    private boolean isPublic = false; // Default to private

    @Column(nullable = false)
    private Long fileSize; // Store file size in bytes

    @Column(length = 50) // e.g., "video/mp4"
    private String mimeType; // Store the validated mime type

    @Column // Nullable until processed
    private Double duration; // Duration in seconds, to be set after processing

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20) // Define length for enum string
    private VideoStatus status = VideoStatus.UPLOADED; // Default status after upload

    public enum VideoStatus {
        UPLOADED, // Initial state after successful upload and metadata save
        PROCESSING, // Video editing/transcoding is in progress
        READY, // Processing complete, video is ready for viewing/download
        FAILED // Processing failed
    }

    // Default constructor required by JPA
    public Video() {
    }

    // Constructor for creating new Video instances (e.g., in Controller)
    public Video(AppUser owner, String generatedFilename, String description, Instant uploadDate, String storagePath, Long fileSize, String mimeType) {
        this.owner = owner;
        this.generatedFilename = generatedFilename; // Use new field name
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

    public AppUser getOwner() {
        return owner;
    }

    public void setOwner(AppUser owner) {
        this.owner = owner;
    }

    // Renamed getter
    public String getGeneratedFilename() {
        return generatedFilename;
    }

    public void setGeneratedFilename(String generatedFilename) {
        this.generatedFilename = generatedFilename;
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

    public String getProcessedStoragePath() {
        return processedStoragePath;
    }

    public void setProcessedStoragePath(String processedStoragePath) {
        this.processedStoragePath = processedStoragePath;
    }

    public boolean isPublic() {
        return isPublic;
    }

    public void setPublic(boolean aPublic) {
        isPublic = aPublic;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
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
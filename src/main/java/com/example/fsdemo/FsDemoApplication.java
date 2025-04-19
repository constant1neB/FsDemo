package com.example.fsdemo;

import com.example.fsdemo.domain.*;
import com.example.fsdemo.repository.AppUserRepository;
import com.example.fsdemo.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@SpringBootApplication
public class FsDemoApplication implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(
            FsDemoApplication.class);

    private static final String USER1_USERNAME = "user1";
    private static final String USER2_USERNAME = "user2";
    private static final String USER_ROLE = "USER";
    private static final String EMAIL_SUFFIX = "@example.com";
    private static final String VIDEO_MIME_TYPE = "video/mp4";

    private final AppUserRepository userRepository;
    private final VideoRepository videoRepository;
    private final PasswordEncoder argon2Encoder;

    private final String testUser1Password;
    private final String testUser2Password;

    public FsDemoApplication(AppUserRepository userRepository,
                             VideoRepository videoRepository,
                             PasswordEncoder argon2Encoder,
                             @Value("${test.user1.password}") String testUser1Password,
                             @Value("${test.user2.password}") String testUser2Password) {
        this.userRepository = userRepository;
        this.videoRepository = videoRepository;
        this.argon2Encoder = argon2Encoder;
        this.testUser1Password = testUser1Password;
        this.testUser2Password = testUser2Password;
    }

    public static void main(String[] args) {
        SpringApplication.run(FsDemoApplication.class, args);
        logger.info("Application started");
    }

    @Override
    @Transactional
    public void run(String... args) {
        logger.info("Seeding initial data...");

        AppUser user1 = findOrCreateUser(USER1_USERNAME, testUser1Password);
        AppUser user2 = findOrCreateUser(USER2_USERNAME, testUser2Password);

        // --- User 1 Videos ---
        if (user1 != null && videoRepository.findByOwnerUsername(USER1_USERNAME).isEmpty()) {
            Video video1 = new Video(user1, "uuid-user1-vid1.mp4", "First test video for user1",
                    Instant.now().minus(2, ChronoUnit.DAYS),
                    "storage/user1/uuid-user1-vid1.mp4", 10240L, VIDEO_MIME_TYPE);
            video1.setStatus(Video.VideoStatus.UPLOADED);
            videoRepository.save(video1);
            logger.info("Created sample video 1 (UPLOADED) for user '{}'", USER1_USERNAME);

            Video video2 = new Video(user1, "uuid-user1-vid2.mp4", "User1 second video, already processed",
                    Instant.now().minus(1, ChronoUnit.HOURS),
                    "storage/user1/uuid-user1-vid2.mp4", 20480L, VIDEO_MIME_TYPE);
            video2.setStatus(Video.VideoStatus.READY);
            video2.setProcessedStoragePath("processed/user1/processed-uuid-user1-vid2.mp4");
            video2.setDuration(15.5);
            videoRepository.save(video2);
            logger.info("Created sample video 2 (READY) for user '{}'", USER1_USERNAME);

            Video video3 = new Video(user1, "uuid-user1-vid3.mp4", "A very long description for user1 third video to test UI wrapping and display limits, hopefully this works okay.",
                    Instant.now(),
                    "storage/user1/uuid-user1-vid3.mp4", 5120L, VIDEO_MIME_TYPE);
            video3.setStatus(Video.VideoStatus.UPLOADED);
            videoRepository.save(video3); // Use renamed field
            logger.info("Created sample video 3 (UPLOADED, long description) for user '{}'", USER1_USERNAME);
        } else if (user1 != null) {
            logger.info("Videos for '{}' already exist.", USER1_USERNAME);
        }

        // --- User 2 Videos ---
        if (user2 != null && videoRepository.findByOwnerUsername(USER2_USERNAME).isEmpty()) {
            Video video4 = new Video(user2, "uuid-user2-vid1.mp4", "User2 only video",
                    Instant.now().minus(5, ChronoUnit.MINUTES),
                    "storage/user2/uuid-user2-vid1.mp4", 15360L, VIDEO_MIME_TYPE);
            video4.setStatus(Video.VideoStatus.UPLOADED);
            videoRepository.save(video4);
            logger.info("Created sample video 1 (UPLOADED) for user '{}'", USER2_USERNAME);

            Video video5 = new Video(user2, "uuid-user2-vid2-failed.mp4", "User2 video that failed processing",
                    Instant.now().minus(10, ChronoUnit.MINUTES),
                    "storage/user2/uuid-user2-vid2-failed.mp4", 8192L, VIDEO_MIME_TYPE);
            video5.setStatus(Video.VideoStatus.FAILED);
            videoRepository.save(video5);
            logger.info("Created sample video 2 (FAILED) for user '{}'", USER2_USERNAME);
        } else if (user2 != null) {
            logger.info("Videos for '{}' already exist.", USER2_USERNAME);
        }

        logger.info("Finished seeding data.");
    }

    /**
     * Finds a user by username or creates and saves a new one if not found.
     * Now takes emailSuffix and role as parameters.
     *
     * @param username    The username to find or create.
     * @param rawPassword The raw password for the new user (will be hashed).
     * @return The found or newly created AppUser.
     */
    private AppUser findOrCreateUser(String username, String rawPassword) {
        return userRepository.findByUsername(username).orElseGet(() -> {
            logger.info("User '{}' not found, creating...", username);
            String hashedPassword = argon2Encoder.encode(rawPassword);
            AppUser newUser = new AppUser(username, hashedPassword, FsDemoApplication.USER_ROLE, username + FsDemoApplication.EMAIL_SUFFIX);
            AppUser savedUser = userRepository.save(newUser);
            logger.info("Created test user '{}'", username);
            return savedUser;
        });
    }
}
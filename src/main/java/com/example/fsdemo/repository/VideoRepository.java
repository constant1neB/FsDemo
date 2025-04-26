package com.example.fsdemo.repository;

import com.example.fsdemo.domain.Video;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoRepository extends CrudRepository<Video, Long> {

    /**
     * Finds a video by its public identifier (UUID string).
     * This is the method used by the Controller to translate the public ID from the URL.
     *
     * @param publicId The public UUID string of the video.
     * @return An Optional containing the video if found.
     */
    Optional<Video> findByPublicId(String publicId);

    /**
     * Finds all videos owned by a specific user based on their username.
     * Uses a JOIN FETCH to eagerly load the owner information, preventing N+1 queries
     * when accessing owner details later.
     *
     * @param username The username of the owner.
     * @return A list of videos owned by the user.
     */
    @Query("SELECT v FROM Video v JOIN FETCH v.owner o WHERE o.username = :username")
    List<Video> findByOwnerUsername(@Param("username") String username);
}

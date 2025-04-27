package com.example.fsdemo.repository;

import com.example.fsdemo.domain.Video;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
     * Finds all videos owned by a specific user based on their username, with pagination.
     * Uses a JOIN FETCH to eagerly load the owner information.
     *
     * @param username The username of the owner.
     * @param pageable Pagination and sorting information.
     * @return A page of videos owned by the user.
     */
    @Query(value = "SELECT v FROM Video v JOIN FETCH v.owner o WHERE o.username = :username",
            countQuery = "SELECT count(v) FROM Video v WHERE v.owner.username = :username")
    Page<Video> findByOwnerUsername(@Param("username") String username, Pageable pageable);
}

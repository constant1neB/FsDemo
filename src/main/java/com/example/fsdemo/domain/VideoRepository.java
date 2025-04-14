package com.example.fsdemo.domain;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VideoRepository extends CrudRepository<Video, Long> {
    @Override
    List<Video> findAll(); // Keep this if needed elsewhere

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

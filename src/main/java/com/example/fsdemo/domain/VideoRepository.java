package com.example.fsdemo.domain;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoRepository extends CrudRepository<Video, Long> {

    List<Video> findByOwnerUsername(@Param("username") String username);

    Optional<Video> findByIdAndOwnerUsername(@Param("id") Long id, @Param("username") String username);

    void delete(@Param("entity") Video entity);

    boolean isPublic(@Param("videoId") Long videoId);

    Optional<Video> findByStoragePath(String storagePath);
}

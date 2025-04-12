package com.example.fsdemo.domain;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface VideoRepository extends CrudRepository<Video, Long> {
    @Override
    List<Video> findAll();
}

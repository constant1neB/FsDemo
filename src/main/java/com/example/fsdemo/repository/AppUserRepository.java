package com.example.fsdemo.repository;

import com.example.fsdemo.domain.AppUser;
import org.springframework.data.repository.CrudRepository;

import java.util.Optional;

public interface AppUserRepository extends CrudRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    // Find by the hashed token now
    Optional<AppUser> findByVerificationTokenHash(String tokenHash);

    Optional<AppUser> findByEmail(String email);

}
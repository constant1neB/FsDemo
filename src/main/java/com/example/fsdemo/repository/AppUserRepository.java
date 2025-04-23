package com.example.fsdemo.repository;

import com.example.fsdemo.domain.AppUser;
import org.springframework.data.repository.CrudRepository;



import java.util.Optional;

public interface AppUserRepository extends CrudRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByVerificationToken(String token);

    Optional<AppUser> findByEmail(String email);

}
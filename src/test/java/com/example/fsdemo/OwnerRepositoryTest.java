package com.example.fsdemo;

import com.example.fsdemo.domain.Owner;
import com.example.fsdemo.domain.OwnerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OwnerRepositoryTest {

    @Autowired
    private OwnerRepository repository;

    @Test
    void SaveOwner() {
        repository.save(new Owner("Lucy", "Smith"));
        assertThat(
                repository.findByFirstname("Lucy").isPresent()
        ).isTrue();
    }

    @Test
    void deleteOwner() {
        repository.save(new Owner("Lisa", "Morrison"));
        repository.deleteAll();
        assertThat(repository.count()).isEqualTo(0);
    }
}

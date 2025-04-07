package com.example.fsdemo;

import com.example.fsdemo.web.CarController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FsDemoApplicationTests {

    @Autowired
    private CarController controller;

    @Test
    @DisplayName("First example test case")
    void contextLoads() {
        assertThat(controller).isNotNull();
    }

}

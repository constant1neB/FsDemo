package com.example.fsdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.web.config.EnableSpringDataWebSupport;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
public class FsDemoApplication {
    private static final Logger logger = LoggerFactory.getLogger(
            FsDemoApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(FsDemoApplication.class, args);
        logger.info("Application started");
    }
}
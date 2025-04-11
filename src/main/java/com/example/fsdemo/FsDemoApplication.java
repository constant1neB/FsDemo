package com.example.fsdemo;

import com.example.fsdemo.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Arrays;

@SpringBootApplication
public class FsDemoApplication implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(
            FsDemoApplication.class);

    private final CarRepository repository;
    private final OwnerRepository orepository;
    private final AppUserRepository urepository;
    private final PasswordEncoder argon2Encoder;

    public FsDemoApplication(CarRepository repository, OwnerRepository orepository,
                             AppUserRepository urepository, PasswordEncoder argon2Encoder) {
        this.repository = repository;
        this.orepository = orepository;
        this.urepository = urepository;
        this.argon2Encoder = argon2Encoder;
    }

    public static void main(String[] args) {
        SpringApplication.run(FsDemoApplication.class, args);
        logger.info("Application started");
    }


    @Override
    public void run(String... args) throws Exception {
        Owner owner1 = new Owner("John", "Doe");
        Owner owner2 = new Owner("Jane", "Eyre");
        orepository.saveAll(Arrays.asList(owner1, owner2));
        String user1pwd = "password";
        String hashedUser1Pwd = argon2Encoder.encode(user1pwd);
        urepository.save(new AppUser("user1", hashedUser1Pwd, "user", "user@example.com"));

        for (Car car : repository.findAll()) {
            logger.info("brand: {}, model: {}",
                    car.getBrand(), car.getModel());
        }
    }
}

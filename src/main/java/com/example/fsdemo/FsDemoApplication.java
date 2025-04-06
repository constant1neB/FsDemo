package com.example.fsdemo;

import com.example.fsdemo.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class FsDemoApplication implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(
            FsDemoApplication.class);

    private final CarRepository repository;
    private final OwnerRepository orepository;
    private final AppUserRepository urepository;

    public FsDemoApplication(CarRepository repository, OwnerRepository orepository, AppUserRepository urepository) {
        this.repository = repository;
        this.orepository = orepository;
        this.urepository = urepository;
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
        repository.save(new Car(
                "Ford",
                "Mustang",
                "Red",
                "ADF-1121",
                2023,
                59000,
                owner1));
        repository.save(new Car(
                "Nissan",
                "Leaf",
                "White",
                "SSJ-3002",
                2020,
                29000,
                owner2));
        repository.save(new Car(
                "Toyota",
                "Hilux",
                "White",
                "KKO-0212",
                2022,
                39000,
                owner2));

        for (Car car : repository.findAll()) {
            logger.info("brand: {}, model: {}",
                    car.getBrand(), car.getModel());
        }
    }
}

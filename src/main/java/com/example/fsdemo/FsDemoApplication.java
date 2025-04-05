package com.example.fsdemo;

import com.example.fsdemo.domain.Car;
import com.example.fsdemo.domain.CarRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class FsDemoApplication implements CommandLineRunner {
	private static final Logger logger = LoggerFactory.getLogger(
			FsDemoApplication.class);

	private final CarRepository repository;

	public FsDemoApplication(CarRepository repository) {
		this.repository = repository;
	}

	public static void main(String[] args) {
		SpringApplication.run(FsDemoApplication.class, args);
		logger.info("Application started");
	}

	@Override
	public void run(String... args) throws Exception {
		repository.save(new Car(
				"Ford",
				"Mustang",
				"Red",
				"ADF-1121",
				2023,
				5900));
		repository.save(new Car(
				"Nissan",
				"Leaf",
				"White",
				"SSJ-3002",
				2020,
				29000));
		repository.save(new Car(
				"Toyota",
				"Hilux",
				"White",
				"KKO-0212",
				2022,
				39000));

		for (Car car : repository.findAll()) {
			logger.info("brand: {}, model: {}",
					car.getBrand(), car.getModel());
		}
	}
}

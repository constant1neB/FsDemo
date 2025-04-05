package com.example.fsdemo.domain;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface CarRepository extends CrudRepository<Car, Long> {
    @Query("select c from Car c where c.brand = ?1") // @Query can affect portability!
    List<Car> findByBrand(String brand);

    List<Car> findByColor(String color);

    List<Car> findByModelYear(int year);

    List<Car> findByBrandAndModel(String brand, String model);

    List<Car> findByBrandOrColor(String brand, String color);

    List<Car> findByBrandOrderByModelAsc(String brand);

    @Query("select c from Car c where c.brand like %?1")
    List<Car> findByBrandEndsWith(String brand);
}

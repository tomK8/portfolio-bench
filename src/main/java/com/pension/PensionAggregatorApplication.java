package com.pension;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PensionAggregatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(PensionAggregatorApplication.class, args);
    }
}

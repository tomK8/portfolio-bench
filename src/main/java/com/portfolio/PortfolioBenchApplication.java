package com.portfolio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PortfolioBenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(PortfolioBenchApplication.class, args);
    }
}

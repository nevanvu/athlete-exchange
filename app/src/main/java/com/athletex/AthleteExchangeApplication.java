package com.athletex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AthleteExchangeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AthleteExchangeApplication.class, args);
    }
}

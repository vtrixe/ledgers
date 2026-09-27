package com.example.ledgers;

import org.springframework.boot.SpringApplication;

public class TestLedgersApplication {

    public static void main(String[] args) {
        SpringApplication.from(LedgersApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}

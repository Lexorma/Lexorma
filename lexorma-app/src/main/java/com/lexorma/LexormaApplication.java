package com.lexorma;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class LexormaApplication {

    public static void main(String[] args) {
        SpringApplication.run(LexormaApplication.class, args);
    }

}

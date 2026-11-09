package com.koboolean.metamora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class MetaMoraApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetaMoraApplication.class, args);
    }

}

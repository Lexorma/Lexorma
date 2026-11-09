package com.koboolean.metamora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class MetamoraApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetamoraApplication.class, args);
    }

}

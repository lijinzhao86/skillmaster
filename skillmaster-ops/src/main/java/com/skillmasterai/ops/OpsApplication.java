package com.skillmasterai.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Content operations: its own process, its own database, no users.
 *
 * <p>Loopback only — CI reaches it over SSH and the server reaches it never. Nothing here
 * serves a browser or a person.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class OpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}

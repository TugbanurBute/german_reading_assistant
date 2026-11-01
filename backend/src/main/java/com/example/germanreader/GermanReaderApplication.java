package com.example.germanreader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GermanReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(GermanReaderApplication.class, args);
    }
}

package com.docbase.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@EnableFeignClients
@SpringBootApplication
public class IngestServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestServiceApplication.class, args);
    }
}

package com.docbase.ingest;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan({"com.docbase.ingest.**.mapper", "com.docbase.ingest.event"})
public class IngestServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IngestServiceApplication.class, args);
    }
}

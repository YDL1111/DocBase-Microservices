package com.docbase.ingest.api;

import com.docbase.common.core.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/ingest")
public class IngestProbeController {
    private final String applicationName;

    public IngestProbeController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/ping")
    ApiResponse<Map<String, String>> ping() {
        return ApiResponse.success(Map.of("service", applicationName, "status", "UP", "stage", "foundation"));
    }
}

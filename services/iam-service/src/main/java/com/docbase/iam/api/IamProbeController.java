package com.docbase.iam.api;

import com.docbase.common.core.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class IamProbeController {
    private final String applicationName;

    public IamProbeController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/ping")
    ApiResponse<Map<String, String>> ping() {
        return ApiResponse.success(Map.of(
                "service", applicationName,
                "status", "UP",
                "stage", "foundation"
        ));
    }
}

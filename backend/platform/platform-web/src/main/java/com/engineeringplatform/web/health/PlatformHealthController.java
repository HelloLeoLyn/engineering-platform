package com.engineeringplatform.web.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.engineeringplatform.web.response.ApiResponse;

@RestController
@RequestMapping("/api/platform")
public class PlatformHealthController {
    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping(@RequestAttribute("requestId") String requestId) {
        return ApiResponse.success(Map.of("status", "UP", "platform", "engineering-platform"), requestId);
    }
}

package com.engineeringplatform.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.engineeringplatform")
public class EngineeringPlatformApplication {
    public static void main(String[] args) { SpringApplication.run(EngineeringPlatformApplication.class, args); }
}

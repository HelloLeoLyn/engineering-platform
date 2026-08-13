package com.engineeringplatform.sample.customer.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSampleCustomerRequest(
        @NotBlank @Size(max = 50) String customerCode,
        @NotBlank @Size(max = 100) String customerName) { }

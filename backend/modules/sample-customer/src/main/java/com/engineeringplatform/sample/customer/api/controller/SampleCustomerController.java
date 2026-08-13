package com.engineeringplatform.sample.customer.api.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.engineeringplatform.sample.customer.api.request.CreateSampleCustomerRequest;
import com.engineeringplatform.sample.customer.api.response.SampleCustomerResponse;
import com.engineeringplatform.sample.customer.application.SampleCustomerApplicationService;
import com.engineeringplatform.web.response.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sample/customers")
public class SampleCustomerController {
    private final SampleCustomerApplicationService applicationService;
    public SampleCustomerController(SampleCustomerApplicationService applicationService) { this.applicationService = applicationService; }

    @PostMapping
    public ApiResponse<SampleCustomerResponse> create(@Valid @RequestBody CreateSampleCustomerRequest request,
            @RequestAttribute("requestId") String requestId) {
        return ApiResponse.success(applicationService.create(request), requestId);
    }

    @GetMapping
    public ApiResponse<List<SampleCustomerResponse>> list(@RequestAttribute("requestId") String requestId) {
        return ApiResponse.success(applicationService.list(), requestId);
    }
}

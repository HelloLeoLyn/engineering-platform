package com.engineeringplatform.sample.customer.application;

import org.mapstruct.Mapper;
import com.engineeringplatform.sample.customer.api.response.SampleCustomerResponse;
import com.engineeringplatform.sample.customer.infrastructure.persistence.SampleCustomerRecord;

@Mapper(componentModel = "spring")
public interface SampleCustomerMapping { SampleCustomerResponse toResponse(SampleCustomerRecord record); }

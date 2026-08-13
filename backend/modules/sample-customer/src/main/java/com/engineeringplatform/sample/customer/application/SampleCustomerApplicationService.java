package com.engineeringplatform.sample.customer.application;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringplatform.sample.customer.api.request.CreateSampleCustomerRequest;
import com.engineeringplatform.sample.customer.api.response.SampleCustomerResponse;
import com.engineeringplatform.sample.customer.infrastructure.persistence.SampleCustomerMapper;
import com.engineeringplatform.sample.customer.infrastructure.persistence.SampleCustomerRecord;

@Service
public class SampleCustomerApplicationService {
    private static final AtomicLong ID_SEQUENCE = new AtomicLong(1000);
    private final SampleCustomerMapper customerMapper;
    private final SampleCustomerMapping mapping;

    public SampleCustomerApplicationService(SampleCustomerMapper customerMapper, SampleCustomerMapping mapping) {
        this.customerMapper = customerMapper;
        this.mapping = mapping;
    }

    @Transactional
    public SampleCustomerResponse create(CreateSampleCustomerRequest request) {
        LocalDateTime now = LocalDateTime.now();
        SampleCustomerRecord record = new SampleCustomerRecord();
        record.setId(ID_SEQUENCE.incrementAndGet());
        record.setCustomerCode(request.customerCode());
        record.setCustomerName(request.customerName());
        record.setEnabled(true);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        customerMapper.insert(record);
        return mapping.toResponse(record);
    }

    @Transactional(readOnly = true)
    public List<SampleCustomerResponse> list() {
        return customerMapper.selectList(null).stream().map(mapping::toResponse).toList();
    }
}

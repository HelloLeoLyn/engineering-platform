package com.engineeringplatform.web.error;
public record FieldValidationError(String field, String messageCode, String message) { }

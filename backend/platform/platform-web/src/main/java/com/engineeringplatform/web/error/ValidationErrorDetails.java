package com.engineeringplatform.web.error;

import java.util.List;

public record ValidationErrorDetails(List<FieldValidationError> fields) {
    public ValidationErrorDetails { fields = fields == null ? List.of() : List.copyOf(fields); }
}

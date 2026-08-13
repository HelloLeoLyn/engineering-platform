package com.engineeringplatform.web.error;

import com.engineeringplatform.core.error.ErrorCode;
import com.engineeringplatform.core.error.ErrorSeverity;

public enum WebErrorCode implements ErrorCode {
    VALIDATION_FAILED("VALIDATION_FAILED", ErrorSeverity.WARNING),
    BAD_REQUEST("BAD_REQUEST", ErrorSeverity.WARNING),
    INTERNAL_ERROR("INTERNAL_ERROR", ErrorSeverity.ERROR);

    private final String code;
    private final ErrorSeverity severity;
    WebErrorCode(String code, ErrorSeverity severity) { this.code = code; this.severity = severity; }
    @Override public String code() { return code; }
    @Override public ErrorSeverity severity() { return severity; }
}

package com.engineeringplatform.core.error;
public interface ErrorCode {
    String code();
    default ErrorSeverity severity() { return ErrorSeverity.ERROR; }
}

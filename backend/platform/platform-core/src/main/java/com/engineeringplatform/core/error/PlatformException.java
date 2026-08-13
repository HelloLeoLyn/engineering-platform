package com.engineeringplatform.core.error;

import java.util.Collections;
import java.util.Map;

public class PlatformException extends RuntimeException {
    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public PlatformException(ErrorCode errorCode) {
        this(errorCode, null, Collections.emptyMap(), null);
    }

    public PlatformException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    public PlatformException(ErrorCode errorCode, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = details == null ? Collections.emptyMap() : Map.copyOf(details);
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, Object> details() { return details; }
}

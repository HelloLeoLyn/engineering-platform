package com.engineeringplatform.web.error;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.engineeringplatform.core.error.PlatformException;
import com.engineeringplatform.web.response.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<ValidationErrorDetails>> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<FieldValidationError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldValidationError).toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(WebErrorCode.VALIDATION_FAILED.code(), "Validation failed",
                        new ValidationErrorDetails(fields), traceId(request)));
    }

    @ExceptionHandler(PlatformException.class)
    public ResponseEntity<ApiResponse<Object>> handlePlatformException(PlatformException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.failure(exception.errorCode().code(), exception.getMessage(), exception.details(), traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure(WebErrorCode.INTERNAL_ERROR.code(), "Internal server error", null, traceId(request)));
    }

    private FieldValidationError toFieldValidationError(FieldError error) {
        return new FieldValidationError(error.getField(), error.getCode(), error.getDefaultMessage());
    }

    private String traceId(HttpServletRequest request) {
        Object requestId = request.getAttribute("requestId");
        return requestId == null ? null : requestId.toString();
    }
}

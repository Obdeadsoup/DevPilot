package com.obdeadsoup.devpilot.framework.error;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.errorCode();
        LOGGER.debug("Business exception response code={} status={}", errorCode.code(), errorCode.status().value());
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.failure(errorCode, exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(CommonErrorCode.INVALID_REQUEST.message());
        return invalidRequest(message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException exception
    ) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .orElse(CommonErrorCode.INVALID_REQUEST.message());
        return invalidRequest(message);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<ApiResponse<Void>> handleUnreadableInput(Exception exception) {
        return invalidRequest(CommonErrorCode.INVALID_REQUEST.message());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> handleUnexpectedException(Exception exception) {
        LOGGER.error("Unhandled exception type={}", exception.getClass().getName());
        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_ERROR.status())
                .body(ApiResponse.failure(CommonErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ApiResponse<Void>> invalidRequest(String message) {
        return ResponseEntity
                .status(CommonErrorCode.INVALID_REQUEST.status())
                .body(ApiResponse.failure(CommonErrorCode.INVALID_REQUEST, message));
    }
}

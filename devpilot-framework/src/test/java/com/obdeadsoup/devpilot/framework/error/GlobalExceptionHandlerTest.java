package com.obdeadsoup.devpilot.framework.error;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsBusinessExceptionToItsErrorCode() {
        BusinessException exception = new BusinessException(
                CommonErrorCode.INVALID_REQUEST,
                "project name is required"
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(exception);

        assertThat(response.getStatusCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST.status());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(CommonErrorCode.INVALID_REQUEST.code());
        assertThat(response.getBody().message()).isEqualTo("project name is required");
    }

    @Test
    void hidesUnexpectedExceptionDetails() {
        RuntimeException exception = new RuntimeException("database password must not be exposed");

        ResponseEntity<ApiResponse<Void>> response = handler.handleUnexpectedException(exception);

        assertThat(response.getStatusCode()).isEqualTo(CommonErrorCode.INTERNAL_ERROR.status());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo(CommonErrorCode.INTERNAL_ERROR.message())
                .doesNotContain("password");
    }

    @Test
    void mapsUnreadableEnumOrJsonInputToSafeBadRequest() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableInput(
                new HttpMessageNotReadableException("private parser detail")
        );

        assertThat(response.getStatusCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST.status());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message())
                .isEqualTo(CommonErrorCode.INVALID_REQUEST.message())
                .doesNotContain("private parser detail");
    }
}

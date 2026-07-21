package com.obdeadsoup.devpilot.framework.api;

import com.obdeadsoup.devpilot.framework.error.CommonErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void createsSuccessfulResponse() {
        ApiResponse<String> response = ApiResponse.success("ready");

        assertThat(response.code()).isEqualTo(CommonErrorCode.SUCCESS.code());
        assertThat(response.message()).isEqualTo(CommonErrorCode.SUCCESS.message());
        assertThat(response.data()).isEqualTo("ready");
    }

    @Test
    void createsFailureWithoutData() {
        ApiResponse<Void> response = ApiResponse.failure(CommonErrorCode.INVALID_REQUEST);

        assertThat(response.code()).isEqualTo(CommonErrorCode.INVALID_REQUEST.code());
        assertThat(response.message()).isEqualTo(CommonErrorCode.INVALID_REQUEST.message());
        assertThat(response.data()).isNull();
    }
}

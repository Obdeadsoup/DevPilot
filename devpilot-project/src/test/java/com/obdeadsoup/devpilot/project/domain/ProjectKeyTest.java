package com.obdeadsoup.devpilot.project.domain;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectKeyTest {

    @Test
    void rejectsNullKey() {
        assertThatThrownBy(() -> ProjectKey.from(null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ProjectErrorCode.INVALID_PROJECT_KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DP", "DEV1", "  api42  ", "ABCDEFGHIJKL"})
    void normalizesAndAcceptsValidKeys(String rawKey) {
        ProjectKey key = ProjectKey.from(rawKey);

        assertThat(key.value()).isEqualTo(rawKey.strip().toUpperCase());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "1DEV", "DEV-1", "DEV_1", "ABCDEFGHIJKLM"})
    void rejectsInvalidKeys(String rawKey) {
        assertThatThrownBy(() -> ProjectKey.from(rawKey))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ProjectErrorCode.INVALID_PROJECT_KEY));
    }
}

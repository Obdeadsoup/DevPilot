package com.obdeadsoup.devpilot.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.obdeadsoup.devpilot.outbox.domain.OutboxEventHandler;
import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxProcessingException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;

class OutboxFailureAndRegistryTest {

    private final OutboxFailureClassifier classifier = new OutboxFailureClassifier();

    @Test
    void classifiesPermanentAndTransientFailuresWithoutLeakingExceptionText() {
        OutboxFailureDecision malformed = classifier.classify(
                new OutboxProcessingException(OutboxFailureType.MALFORMED_PAYLOAD, "secret payload"));
        OutboxFailureDecision database = classifier.classify(
                new TransientDataAccessResourceException("jdbc-url-with-secret"));

        assertThat(malformed.retryable()).isFalse();
        assertThat(malformed.errorCode()).isEqualTo("MALFORMED_PAYLOAD");
        assertThat(malformed.safeMessage()).doesNotContain("secret");
        assertThat(database.retryable()).isTrue();
        assertThat(database.failureType()).isEqualTo(OutboxFailureType.TRANSIENT_DATABASE);
    }

    @Test
    void rejectsDuplicateRegistrationAndClassifiesUnknownOrUnsupportedHandler() {
        OutboxEventHandler first = handler("TASK_ASSIGNED_V1", 1);
        OutboxEventHandler duplicate = handler("TASK_ASSIGNED_V1", 1);
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> new OutboxHandlerRegistry(List.of(first, duplicate)));

        OutboxHandlerRegistry registry = new OutboxHandlerRegistry(List.of(first));
        assertFailure(registry, "TASK_ASSIGNED_V1", 2, OutboxFailureType.UNSUPPORTED_SCHEMA);
        assertFailure(registry, "UNKNOWN", 1, OutboxFailureType.UNKNOWN_EVENT_TYPE);
    }

    private OutboxEventHandler handler(String type, int schema) {
        OutboxEventHandler handler = mock(OutboxEventHandler.class);
        when(handler.supportedEventType()).thenReturn(type);
        when(handler.supportedSchemaVersion()).thenReturn(schema);
        return handler;
    }

    private void assertFailure(
            OutboxHandlerRegistry registry, String type, int schema, OutboxFailureType expected) {
        try {
            registry.require(type, schema);
            throw new AssertionError("expected failure");
        } catch (OutboxProcessingException exception) {
            assertThat(exception.failureType()).isEqualTo(expected);
            assertThat(classifier.classify(exception).retryable()).isFalse();
        }
    }
}

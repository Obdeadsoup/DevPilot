package com.obdeadsoup.devpilot.notification.sse;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.obdeadsoup.devpilot.notification.application.NotificationQueryService;
import com.obdeadsoup.devpilot.notification.event.NotificationCommittedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationSsePublisherTest {

    @Test
    void channelFailureDoesNotEscapeAfterCommitListener() {
        NotificationSseRegistry registry = mock(NotificationSseRegistry.class);
        NotificationQueryService queries = mock(NotificationQueryService.class);
        when(queries.unreadCountForRecipient(20)).thenThrow(new IllegalStateException("channel unavailable"));
        NotificationSsePublisher publisher = new NotificationSsePublisher(registry, queries);

        assertThatCode(() -> publisher.afterCommit(
                new NotificationCommittedEvent(101, 20, LocalDateTime.of(2026, 8, 10, 12, 0))))
                .doesNotThrowAnyException();
    }
}

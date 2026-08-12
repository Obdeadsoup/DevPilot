package com.obdeadsoup.devpilot.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.audit.domain.AuditActionType;
import com.obdeadsoup.devpilot.audit.domain.AuditResourceType;
import com.obdeadsoup.devpilot.audit.domain.AuditResult;
import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdAccessor;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditCommandFactoryCorrelationTest {
    @Test
    void highRiskAuditCommandUsesCurrentHttpCorrelationId() {
        CorrelationIdAccessor accessor = new CorrelationIdAccessor();
        AuditCommandFactory factory = new AuditCommandFactory(accessor);
        try (var ignored = accessor.open("http-replay-123")) {
            var command = factory.user(10, 20, 30, AuditActionType.OUTBOX_REPLAY_REQUESTED,
                    AuditResourceType.OUTBOX_EVENT, 40, AuditResult.SUCCESS, "reason", null, Map.of());
            assertThat(command.correlationId()).isEqualTo("http-replay-123");
        }
        assertThat(accessor.current()).isEmpty();
    }
}

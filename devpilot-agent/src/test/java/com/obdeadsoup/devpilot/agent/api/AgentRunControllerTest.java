package com.obdeadsoup.devpilot.agent.api;

import com.obdeadsoup.devpilot.agent.api.dto.StartAgentRunRequest;
import com.obdeadsoup.devpilot.agent.application.AgentRunApplicationService;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRunView;
import com.obdeadsoup.devpilot.agent.application.AgentRunHistoryItem;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunControllerTest {
    private final AgentRunApplicationService service = mock(AgentRunApplicationService.class);
    private final AgentRunController controller = new AgentRunController(service);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void requestRejectsBlankAndOversizedInput() {
        assertThat(validator.validate(new StartAgentRunRequest("   "))).isNotEmpty();
        assertThat(validator.validate(new StartAgentRunRequest("a".repeat(10_001)))).isNotEmpty();
        assertThat(validator.validate(new StartAgentRunRequest("explain this change"))).isEmpty();
    }

    @Test
    void wrapsApplicationProjectionInStandardApiResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 12, 0);
        AgentRunView view = new AgentRunView("run-1", "request-1", 1, 2, 7,
                AgentRunStatus.RUNNING, "hello", null, null,
                now, null, now, now, 0);
        when(service.start(1, 2, "hello")).thenReturn(view);

        var response = controller.start(1, 2, new StartAgentRunRequest("hello"));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON_0000");
        assertThat(response.getBody().data().status()).isEqualTo(AgentRunStatus.RUNNING);
        verify(service).start(1, 2, "hello");
    }

    @Test
    void permissionErrorFromApplicationBoundaryIsNotSwallowed() {
        BusinessException denied = new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        when(service.start(1, 2, "hello")).thenThrow(denied);

        assertThatThrownBy(() -> controller.start(1, 2, new StartAgentRunRequest("hello")))
                .isSameAs(denied);
    }

    @Test
    void cancelReturnsCancelledProjection() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 12, 0);
        AgentRunView view = new AgentRunView("run-1", "request-1", 1, 2, 7,
                AgentRunStatus.CANCELLED, "hello", null, null,
                now, now, now, now, 1);
        when(service.cancel(1, 2, "run-1")).thenReturn(view);

        var response = controller.cancel(1, 2, "run-1");

        assertThat(response.data().status()).isEqualTo(AgentRunStatus.CANCELLED);
        verify(service).cancel(1, 2, "run-1");
    }

    @Test
    void historyReturnsPageOfSummaryOnly() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 25, 12, 0);
        when(service.listHistory(1, 2, AgentRunStatus.SUCCEEDED, 0, 20)).thenReturn(
                new PageResponse<>(0, 20, 1, List.of(new AgentRunHistoryItem(
                        "run-1", AgentRunStatus.SUCCEEDED, null, now, now, now))));

        var response = controller.list(1, 2, AgentRunStatus.SUCCEEDED, 0, 20);

        assertThat(response.data().items()).hasSize(1);
        assertThat(response.data().items().getFirst().runId()).isEqualTo("run-1");
        verify(service).listHistory(1, 2, AgentRunStatus.SUCCEEDED, 0, 20);
    }
}

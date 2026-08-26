package com.obdeadsoup.devpilot.agent.api;

import com.obdeadsoup.devpilot.agent.api.dto.StartAgentRunRequest;
import com.obdeadsoup.devpilot.agent.application.AgentRunApplicationService;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRunView;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

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
}

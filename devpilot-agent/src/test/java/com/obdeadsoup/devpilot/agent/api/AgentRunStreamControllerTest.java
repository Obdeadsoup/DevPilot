package com.obdeadsoup.devpilot.agent.api;

import com.obdeadsoup.devpilot.agent.application.AgentRunApplicationService;
import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.agent.sse.AgentRunEventHub;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunStreamControllerTest {
    private final AgentRunApplicationService applicationService = mock(AgentRunApplicationService.class);
    private final AgentRunEventHub eventHub = mock(AgentRunEventHub.class);
    private final AgentRunStreamController controller =
            new AgentRunStreamController(applicationService, eventHub);

    @Test
    void checksScopedReadBeforeRegisteringEmitterAndParsesLastEventId() {
        SseEmitter emitter = new SseEmitter();
        when(eventHub.register("run-1", 5L)).thenReturn(emitter);

        assertThat(controller.stream(1, 2, "run-1", "run-1:5")).isSameAs(emitter);

        var order = inOrder(applicationService, eventHub);
        order.verify(applicationService).get(1, 2, "run-1");
        order.verify(eventHub).register("run-1", 5L);
    }

    @Test
    void rejectsMalformedOrCrossRunLastEventIdAfterScopedRead() {
        assertThatThrownBy(() -> controller.stream(1, 2, "run-1", "other:5"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(AgentRunErrorCode.INVALID_LAST_EVENT_ID));
        verify(applicationService).get(1, 2, "run-1");

        assertThatThrownBy(() -> controller.stream(1, 2, "run-1", "run-1:zero"))
                .isInstanceOf(BusinessException.class);
    }
}

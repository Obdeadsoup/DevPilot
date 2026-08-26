package com.obdeadsoup.devpilot.agent.sse;

import com.obdeadsoup.devpilot.agent.api.AgentRunStreamController;
import com.obdeadsoup.devpilot.agent.application.AgentRunApplicationService;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.config.AgentRunSseProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentRunSseMvcTest {
    @Test
    void normalConnectionSerializesSseIdNameDataAndHeartbeat() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AgentRunEventHub hub = new AgentRunEventHub(
                new AgentRunSseProperties(true, Duration.ofMinutes(1), Duration.ofSeconds(20),
                        2, 8, Duration.ofMinutes(5)),
                meters,
                new AgentRunStreamMetrics(meters));
        AgentRunApplicationService application = mock(AgentRunApplicationService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new AgentRunStreamController(application, hub)).build();
        hub.initialize("run-1");

        MvcResult stream = mvc.perform(get(
                        "/api/v1/workspaces/1/projects/2/agent-runs/run-1/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        hub.heartbeat();
        hub.publish(event(1, AgentStreamEventType.RUN_STARTED, ""));
        hub.publish(event(2, AgentStreamEventType.RUN_SUCCEEDED, "answer"));

        String body = mvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains(
                "event:heartbeat",
                "event:run-started",
                "id:run-1:1",
                "\"runId\":\"run-1\"",
                "\"sequence\":1",
                "event:run-succeeded",
                "id:run-1:2",
                "\"finalOutput\":\"answer\"");
        verify(application).get(1, 2, "run-1");
        assertThat(meters.get("devpilot.agent.sse.send").counters()).isNotEmpty();
    }

    private AgentStreamEvent event(long sequence, AgentStreamEventType type, String output) {
        return new AgentStreamEvent("run-1:" + sequence, "run-1", sequence, type,
                0, "", output, "");
    }
}

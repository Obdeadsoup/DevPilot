package com.obdeadsoup.devpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IsolatedPersistenceTestConfiguration.class)
class ObservabilityActuatorIntegrationTest {
    @Autowired MockMvc mvc;

    @Test
    void healthProbesAreAnonymousAndSensitiveEndpointsStayUnavailable() throws Exception {
        String health = mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(health).contains("\"status\":\"UP\"")
                .doesNotContain("components", "jdbc", "redis", "password");
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/livez")).andExpect(status().isOk());
        mvc.perform(get("/readyz")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/heapdump")).andExpect(status().isUnauthorized());
    }
}

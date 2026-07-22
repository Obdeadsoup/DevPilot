package com.obdeadsoup.devpilot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
@Import(IsolatedPersistenceTestConfiguration.class)
class DevPilotApplicationTests {

    @Test
    void contextLoads() {
    }
}

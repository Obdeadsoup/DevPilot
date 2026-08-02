package com.obdeadsoup.devpilot.github.application;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class GitHubSyncSchedulerTest {

    @Test
    void delegatesOneScanWithoutOwningBusinessState() {
        GitHubSyncRunService runService = mock(GitHubSyncRunService.class);

        new GitHubSyncScheduler(runService).scan();

        verify(runService).discoverAndSubmit();
    }
}

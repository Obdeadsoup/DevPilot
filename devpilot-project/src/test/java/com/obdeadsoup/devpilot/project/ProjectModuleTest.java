package com.obdeadsoup.devpilot.project;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectModuleTest {

    @Test
    void moduleMarkerCanBeLoaded() throws ClassNotFoundException {
        Class<?> marker = Class.forName("com.obdeadsoup.devpilot.project.ProjectModule");

        assertThat(marker).isEqualTo(ProjectModule.class);
    }
}

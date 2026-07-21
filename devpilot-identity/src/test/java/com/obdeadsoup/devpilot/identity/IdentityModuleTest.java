package com.obdeadsoup.devpilot.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityModuleTest {

    @Test
    void moduleMarkerCanBeLoaded() throws ClassNotFoundException {
        Class<?> marker = Class.forName("com.obdeadsoup.devpilot.identity.IdentityModule");

        assertThat(marker).isEqualTo(IdentityModule.class);
    }
}

package com.obdeadsoup.devpilot.github.application.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookSignatureVerifierTest {

    private final GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier();

    @Test
    void verifiesGitHubOfficialTestVector() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        boolean valid = verifier.verify(
                payload,
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
                "It's a Secret to Everybody"
        );

        assertThat(valid).isTrue();
    }

    @Test
    void rejectsWrongOrMalformedSignature() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(payload, "sha256=" + "0".repeat(64), "secret")).isFalse();
        assertThat(verifier.verify(payload, "sha1=abcd", "secret")).isFalse();
        assertThat(verifier.verify(payload, "sha256=not-hex", "secret")).isFalse();
    }
}

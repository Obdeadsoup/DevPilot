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
    void rejectsSignatureAfterOnePayloadByteChanges() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);
        byte[] changedPayload = "Hello, World?".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(
                changedPayload,
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
                "It's a Secret to Everybody"
        )).isFalse();
    }

    @Test
    void rejectsSignatureWithoutSha256Prefix() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(
                payload,
                "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
                "It's a Secret to Everybody"
        )).isFalse();
    }

    @Test
    void rejectsIllegalHexadecimalSignature() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(payload, "sha256=" + "g".repeat(64), "secret")).isFalse();
    }

    @Test
    void rejectsEmptySecret() {
        byte[] payload = "Hello, World!".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.verify(
                payload,
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
                ""
        )).isFalse();
    }
}

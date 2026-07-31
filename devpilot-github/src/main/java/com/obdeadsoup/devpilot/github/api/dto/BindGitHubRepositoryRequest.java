package com.obdeadsoup.devpilot.github.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BindGitHubRepositoryRequest(
        @NotBlank @Size(max = 39) String owner,
        @NotBlank @Size(max = 100) String repositoryName,
        @NotBlank @Size(max = 200) String apiCredentialRef,
        @NotBlank @Size(max = 200) String webhookSecretRef
) {
}

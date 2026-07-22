package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepositoryPayload(
        Long id,
        @JsonProperty("full_name") String fullName
) {
}

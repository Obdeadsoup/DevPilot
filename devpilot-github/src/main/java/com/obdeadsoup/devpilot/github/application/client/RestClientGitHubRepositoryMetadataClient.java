package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;
import com.obdeadsoup.devpilot.github.error.GitHubRepositoryErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;

@Component
public class RestClientGitHubRepositoryMetadataClient implements GitHubRepositoryMetadataClient {

    static final String API_BASE_URL = "https://api.github.com";
    private static final String API_VERSION = "2022-11-28";
    private static final String USER_AGENT = "DevPilot/0.0.1";

    private final RestClient restClient;

    @Autowired
    public RestClientGitHubRepositoryMetadataClient(
            RestClient.Builder builder,
            GitHubIntegrationProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = builder.clone()
                .baseUrl(API_BASE_URL)
                .requestFactory(requestFactory)
                .build();
    }

    RestClientGitHubRepositoryMetadataClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public VerifiedGitHubRepository getRepository(
            String owner,
            String repositoryName,
            String apiToken
    ) {
        try {
            return verified(fetchWithOneSafeRedirect(owner, repositoryName, apiToken));
        } catch (GitHubApiRedirect exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE);
        } catch (HttpClientErrorException.Unauthorized exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_API_AUTHENTICATION_FAILED);
        } catch (HttpClientErrorException.Forbidden exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_API_FORBIDDEN);
        } catch (HttpClientErrorException.NotFound exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE);
        } catch (HttpClientErrorException.TooManyRequests exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_API_RATE_LIMITED);
        } catch (HttpServerErrorException | ResourceAccessException exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_API_UNAVAILABLE);
        } catch (HttpClientErrorException exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE);
        } catch (RestClientException exception) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_API_RESPONSE_INVALID);
        }
    }

    private RepositoryResponse fetchWithOneSafeRedirect(
            String owner,
            String repositoryName,
            String apiToken
    ) {
        try {
            return execute(
                    restClient.get().uri(uriBuilder ->
                            uriBuilder.pathSegment("repos", owner, repositoryName).build()
                    ),
                    apiToken
            );
        } catch (GitHubApiRedirect redirect) {
            URI location = requireSafeRedirect(redirect.location());
            return execute(restClient.get().uri(location), apiToken);
        }
    }

    private RepositoryResponse execute(
            RestClient.RequestHeadersSpec<?> request,
            String apiToken
    ) {
        return request
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiToken)
                .header("X-GitHub-Api-Version", API_VERSION)
                .header(HttpHeaders.USER_AGENT, USER_AGENT)
                .retrieve()
                .onStatus(status -> status.is3xxRedirection(), (httpRequest, response) -> {
                    throw new GitHubApiRedirect(response.getHeaders().getLocation());
                })
                .body(RepositoryResponse.class);
    }

    private URI requireSafeRedirect(URI location) {
        if (location == null
                || !"https".equalsIgnoreCase(location.getScheme())
                || !"api.github.com".equalsIgnoreCase(location.getHost())
                || (location.getPort() != -1 && location.getPort() != 443)
                || location.getUserInfo() != null
                || location.getFragment() != null
                || location.getRawPath() == null
                || location.getRawPath().isBlank()) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE);
        }
        return location;
    }

    private VerifiedGitHubRepository verified(RepositoryResponse response) {
        if (response == null
                || response.id() == null
                || response.id() <= 0
                || response.owner() == null
                || isBlank(response.owner().login())
                || isBlank(response.name())
                || isBlank(response.fullName())
                || isBlank(response.htmlUrl())
                || isBlank(response.visibility())) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_API_RESPONSE_INVALID);
        }
        return new VerifiedGitHubRepository(
                response.id(),
                response.owner().login(),
                response.name(),
                response.fullName(),
                response.htmlUrl(),
                response.defaultBranch(),
                response.visibility()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RepositoryResponse(
            Long id,
            OwnerResponse owner,
            String name,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("default_branch") String defaultBranch,
            String visibility
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OwnerResponse(String login) {
    }

    private static final class GitHubApiRedirect extends RuntimeException {

        private final URI location;

        private GitHubApiRedirect(URI location) {
            super("GitHub API redirect");
            this.location = location;
        }

        private URI location() {
            return location;
        }
    }
}

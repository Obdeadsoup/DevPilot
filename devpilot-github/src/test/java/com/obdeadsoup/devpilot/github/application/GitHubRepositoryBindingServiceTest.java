package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.api.dto.GitHubRepositoryResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.client.VerifiedGitHubRepository;
import com.obdeadsoup.devpilot.github.application.credential.GitHubApiCredentialResolver;
import com.obdeadsoup.devpilot.github.application.secret.WebhookSecretResolver;
import com.obdeadsoup.devpilot.github.error.GitHubRepositoryErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubRepositoryBindingServiceTest {

    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 100L;
    private static final long PROJECT_ID = 200L;
    private static final long BINDING_ID = 300L;
    private static final String API_REFERENCE = "DEVPILOT_GITHUB_API_TOKEN_TEST";
    private static final String WEBHOOK_REFERENCE = "DEVPILOT_GITHUB_WEBHOOK_SECRET_TEST";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 31, 12, 0);

    private CurrentUserProvider currentUserProvider;
    private ProjectAuthorizationService authorizationService;
    private GitHubRepositoryMapper repositoryMapper;
    private GitHubApiCredentialResolver apiCredentialResolver;
    private WebhookSecretResolver webhookSecretResolver;
    private GitHubRepositoryMetadataClient metadataClient;
    private GitHubRepositoryBindingService service;

    @BeforeEach
    void setUp() {
        currentUserProvider = mock(CurrentUserProvider.class);
        authorizationService = mock(ProjectAuthorizationService.class);
        repositoryMapper = mock(GitHubRepositoryMapper.class);
        apiCredentialResolver = mock(GitHubApiCredentialResolver.class);
        webhookSecretResolver = mock(WebhookSecretResolver.class);
        metadataClient = mock(GitHubRepositoryMetadataClient.class);
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        service = new GitHubRepositoryBindingService(
                currentUserProvider,
                authorizationService,
                repositoryMapper,
                apiCredentialResolver,
                webhookSecretResolver,
                metadataClient,
                Clock.fixed(Instant.parse("2026-07-31T12:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void bindUsesOnlyVerifiedMetadataAndCurrentAuthenticatedUser() {
        VerifiedGitHubRepository verified = verified(123456L, "trusted-org", "trusted-repo");
        GitHubRepositoryEntity stored = binding(
                "ACTIVE", 0, verified.githubRepositoryId(), verified.ownerLogin(), verified.repositoryName()
        );
        when(apiCredentialResolver.resolve(API_REFERENCE)).thenReturn(Optional.of("api-token"));
        when(webhookSecretResolver.resolve(WEBHOOK_REFERENCE)).thenReturn(Optional.of("webhook-secret"));
        when(metadataClient.getRepository("client-owner", "client-repo", "api-token"))
                .thenReturn(verified);
        when(repositoryMapper.findByGitHubRepositoryId(verified.githubRepositoryId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(stored));
        when(repositoryMapper.findActiveByWorkspaceAndFullName(WORKSPACE_ID, verified.fullName()))
                .thenReturn(Optional.empty());
        when(repositoryMapper.insert(
                WORKSPACE_ID,
                PROJECT_ID,
                verified.githubRepositoryId(),
                verified.ownerLogin(),
                verified.repositoryName(),
                verified.fullName(),
                verified.htmlUrl(),
                verified.defaultBranch(),
                verified.visibility(),
                WEBHOOK_REFERENCE,
                API_REFERENCE,
                NOW,
                USER_ID
        )).thenReturn(1);

        GitHubRepositoryResponse response = service.bindRepository(
                WORKSPACE_ID,
                PROJECT_ID,
                " client-owner ",
                " client-repo ",
                API_REFERENCE,
                WEBHOOK_REFERENCE
        );

        assertThat(response.githubRepositoryId()).isEqualTo(123456L);
        assertThat(response.ownerLogin()).isEqualTo("trusted-org");
        assertThat(response.repositoryName()).isEqualTo("trusted-repo");
        assertThat(response.hasApiCredential()).isTrue();
        assertThat(response.hasWebhookSecret()).isTrue();
        verify(authorizationService).requirePermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.REPOSITORY_BIND
        );
    }

    @Test
    void invalidRepositoryReferenceStopsBeforeCredentialResolutionOrNetworkCall() {
        assertThatThrownBy(() -> service.bindRepository(
                WORKSPACE_ID,
                PROJECT_ID,
                "https://evil.example",
                "demo",
                API_REFERENCE,
                WEBHOOK_REFERENCE
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.INVALID_REPOSITORY_REFERENCE));

        verify(apiCredentialResolver, never()).resolve(API_REFERENCE);
        verify(metadataClient, never()).getRepository(
                "https://evil.example", "demo", "api-token"
        );
    }

    @Test
    void refreshRejectsChangedStableRepositoryIdWithoutUpdatingMetadata() {
        GitHubRepositoryEntity binding = binding("ACTIVE", 3, 123456L, "octo", "demo");
        when(repositoryMapper.findByScope(WORKSPACE_ID, PROJECT_ID, BINDING_ID))
                .thenReturn(Optional.of(binding));
        when(apiCredentialResolver.resolve(API_REFERENCE)).thenReturn(Optional.of("api-token"));
        when(metadataClient.getRepository("octo", "demo", "api-token"))
                .thenReturn(verified(999999L, "renamed", "repository"));

        assertThatThrownBy(() -> service.refreshRepositoryMetadata(
                WORKSPACE_ID, PROJECT_ID, BINDING_ID, 3
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_ID_MISMATCH));

        verify(repositoryMapper, never()).refreshMetadata(
                WORKSPACE_ID,
                PROJECT_ID,
                BINDING_ID,
                3,
                "renamed",
                "repository",
                "renamed/repository",
                "https://github.com/renamed/repository",
                "main",
                "private",
                NOW
        );
    }

    @Test
    void disableRejectsDisabledBindingAndStaleVersionWithStableErrors() {
        when(repositoryMapper.findByScope(WORKSPACE_ID, PROJECT_ID, BINDING_ID))
                .thenReturn(Optional.of(binding("DISABLED", 4, 123456L, "octo", "demo")));

        assertThatThrownBy(() -> service.disableRepository(
                WORKSPACE_ID, PROJECT_ID, BINDING_ID, 4
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.REPOSITORY_BINDING_DISABLED));
        assertThatThrownBy(() -> service.disableRepository(
                WORKSPACE_ID, PROJECT_ID, BINDING_ID, 3
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.REPOSITORY_BINDING_VERSION_CONFLICT));
        verify(repositoryMapper, never()).disable(WORKSPACE_ID, PROJECT_ID, BINDING_ID, 4);
    }

    @Test
    void reactivateRequiresBothCredentialTypes() {
        when(repositoryMapper.findByScope(WORKSPACE_ID, PROJECT_ID, BINDING_ID))
                .thenReturn(Optional.of(binding("DISABLED", 2, 123456L, "octo", "demo")));
        when(apiCredentialResolver.resolve(API_REFERENCE)).thenReturn(Optional.of("api-token"));
        when(webhookSecretResolver.resolve(WEBHOOK_REFERENCE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reactivateRepository(
                WORKSPACE_ID, PROJECT_ID, BINDING_ID, 2
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.WEBHOOK_SECRET_UNAVAILABLE));
        verify(metadataClient, never()).getRepository("octo", "demo", "api-token");
    }

    private GitHubRepositoryEntity binding(
            String status,
            long version,
            long githubRepositoryId,
            String owner,
            String repositoryName
    ) {
        return new GitHubRepositoryEntity(
                BINDING_ID,
                WORKSPACE_ID,
                PROJECT_ID,
                githubRepositoryId,
                owner,
                repositoryName,
                owner + "/" + repositoryName,
                "https://github.com/" + owner + "/" + repositoryName,
                "main",
                "private",
                status,
                WEBHOOK_REFERENCE,
                API_REFERENCE,
                null,
                NOW,
                USER_ID,
                NOW,
                NOW,
                version
        );
    }

    private VerifiedGitHubRepository verified(long id, String owner, String repositoryName) {
        return new VerifiedGitHubRepository(
                id,
                owner,
                repositoryName,
                owner + "/" + repositoryName,
                "https://github.com/" + owner + "/" + repositoryName,
                "main",
                "private"
        );
    }
}

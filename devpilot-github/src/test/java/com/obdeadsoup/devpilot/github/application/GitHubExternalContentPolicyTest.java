package com.obdeadsoup.devpilot.github.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GitHubExternalContentPolicyTest{
    private final GitHubExternalContentPolicy policy=new GitHubExternalContentPolicy(new ObjectMapper());
    @Test void safelyTruncatesUntrustedTextAndKeepsJsonValid() throws Exception{assertThat(policy.body("x".repeat(10001))).hasSize(10000);
        String json=policy.summaryJson("[\"z\",\"a\",\"a\"]");assertThat(new ObjectMapper().readTree(json).toString()).isEqualTo("[\"a\",\"z\"]");}
    @Test void onlyAcceptsGitHubHttpsUrl(){assertThatThrownBy(()->policy.githubUrl("javascript:alert(1)"))
            .isInstanceOf(BusinessException.class);}
    @Test void contentHashIsStableAndSensitiveToOneCharacter(){assertThat(policy.contentHash("a")).hasSize(64)
            .isNotEqualTo(policy.contentHash("b"));}
}

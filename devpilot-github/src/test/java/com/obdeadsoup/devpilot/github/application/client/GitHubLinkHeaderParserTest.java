package com.obdeadsoup.devpilot.github.application.client;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubLinkHeaderParserTest {

    private final GitHubLinkHeaderParser parser = new GitHubLinkHeaderParser(
            new GitHubApiEndpointPolicy(URI.create("https://api.github.com"), false)
    );

    @Test
    void parsesNextAndAllKnownRelationsWithoutNaiveCommaSplit() {
        GitHubPageCursor cursor = parser.parse("""
                <https://api.github.com/repositories/1/issues?page=2&labels=a,b>; rel="next", \
                <https://api.github.com/repositories/1/issues?page=1>; rel="prev first", \
                <https://api.github.com/repositories/1/issues?page=8>; rel="last"
                """);

        assertThat(cursor.next().toString()).contains("page=2&labels=a,b");
        assertThat(cursor.previous().toString()).contains("page=1");
        assertThat(cursor.first()).isEqualTo(cursor.previous());
        assertThat(cursor.last().toString()).contains("page=8");
    }

    @Test
    void missingNextProducesSafeEmptyCursor() {
        GitHubPageCursor cursor = parser.parse(
                "<https://api.github.com/repositories/1/issues?page=1>; rel=prev"
        );

        assertThat(cursor.hasNext()).isFalse();
        assertThat(parser.parse(null)).isEqualTo(GitHubPageCursor.empty());
    }

    @Test
    void rejectsOffHostAndUserInfoPaginationUrls() {
        assertThatThrownBy(() -> parser.parse(
                "<https://evil.example/issues?page=2>; rel=next"
        )).isInstanceOfSatisfying(GitHubApiException.class, exception ->
                assertThat(exception.failureType()).isEqualTo(GitHubApiFailureType.VALIDATION));
        assertThatThrownBy(() -> parser.parse(
                "<https://user@api.github.com/issues?page=2>; rel=next"
        )).isInstanceOf(GitHubApiException.class);
    }

    @Test
    void permitsConfiguredLoopbackHostOnlyForTestPolicy() {
        assertThatThrownBy(() -> new GitHubApiEndpointPolicy(
                URI.create("http://127.0.0.1:45678"), false
        )).isInstanceOf(IllegalStateException.class);
        GitHubLinkHeaderParser testParser = new GitHubLinkHeaderParser(
                new GitHubApiEndpointPolicy(URI.create("http://127.0.0.1:45678"), true)
        );

        GitHubPageCursor cursor = testParser.parse(
                "<http://127.0.0.1:45678/page/2>; rel=next"
        );

        assertThat(cursor.next()).isEqualTo(URI.create("http://127.0.0.1:45678/page/2"));
        assertThatThrownBy(() -> testParser.parse(
                "<http://127.0.0.1:45679/page/2>; rel=next"
        )).isInstanceOf(GitHubApiException.class);
    }
}

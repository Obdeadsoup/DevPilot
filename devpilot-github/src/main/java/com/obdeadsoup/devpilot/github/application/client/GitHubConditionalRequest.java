package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

/** Repository Metadata 刷新使用的 ETag / Last-Modified 条件请求参数。 */
public record GitHubConditionalRequest(String etag, Instant lastModified) {

    public static GitHubConditionalRequest none() {
        return new GitHubConditionalRequest(null, null);
    }

    public boolean hasValidators() {
        return etag != null || lastModified != null;
    }
}

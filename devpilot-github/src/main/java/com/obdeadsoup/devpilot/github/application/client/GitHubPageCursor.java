package com.obdeadsoup.devpilot.github.application.client;

import java.net.URI;

/** 只保存已通过 Endpoint Policy 校验的 GitHub Link Header 分页地址。 */
public record GitHubPageCursor(URI next, URI previous, URI first, URI last) {

    public static GitHubPageCursor empty() {
        return new GitHubPageCursor(null, null, null, null);
    }

    public boolean hasNext() {
        return next != null;
    }
}

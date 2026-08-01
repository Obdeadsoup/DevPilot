package com.obdeadsoup.devpilot.github.application.client;

import java.util.List;

/** GitHub 列表 API 的单页结果，下一页只能通过已验证的 Link Cursor 继续。 */
public record GitHubPage<T>(List<T> items, GitHubPageCursor cursor) {

    public GitHubPage {
        items = List.copyOf(items);
        cursor = cursor == null ? GitHubPageCursor.empty() : cursor;
    }
}

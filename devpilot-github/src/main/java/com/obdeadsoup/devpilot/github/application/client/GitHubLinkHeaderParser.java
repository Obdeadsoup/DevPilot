package com.obdeadsoup.devpilot.github.application.client;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析 GitHub RFC 8288 Link Header，并只返回通过 Endpoint Policy 的分页 Cursor。
 *
 * <p>不能简单按逗号 split：合法 URI 的 {@code <...>} 内也可能出现逗号。更不能盲目访问上游
 * 返回的任意 next URL，否则 Bearer Token 可能被 SSRF 重定向到攻击者 Host。</p>
 */
public final class GitHubLinkHeaderParser {

    private final GitHubApiEndpointPolicy endpointPolicy;

    public GitHubLinkHeaderParser(GitHubApiEndpointPolicy endpointPolicy) {
        this.endpointPolicy = endpointPolicy;
    }

    public GitHubPageCursor parse(String header) {
        if (header == null || header.isBlank()) {
            return GitHubPageCursor.empty();
        }
        URI next = null;
        URI previous = null;
        URI first = null;
        URI last = null;
        for (String linkValue : splitLinkValues(header)) {
            ParsedLink parsed = parseLink(linkValue);
            if (parsed == null) {
                continue;
            }
            URI uri = endpointPolicy.requireAllowed(parsed.uri());
            for (String relation : parsed.relations()) {
                switch (relation) {
                    case "next" -> next = uri;
                    case "prev" -> previous = uri;
                    case "first" -> first = uri;
                    case "last" -> last = uri;
                    default -> {
                    }
                }
            }
        }
        return new GitHubPageCursor(next, previous, first, last);
    }

    private List<String> splitLinkValues(String header) {
        List<String> values = new ArrayList<>();
        int start = 0;
        boolean insideUri = false;
        boolean insideQuotes = false;
        for (int index = 0; index < header.length(); index++) {
            char current = header.charAt(index);
            if (current == '<' && !insideQuotes) {
                insideUri = true;
            } else if (current == '>' && !insideQuotes) {
                insideUri = false;
            } else if (current == '"' && !insideUri
                    && (index == 0 || header.charAt(index - 1) != '\\')) {
                insideQuotes = !insideQuotes;
            } else if (current == ',' && !insideUri && !insideQuotes) {
                values.add(header.substring(start, index).trim());
                start = index + 1;
            }
        }
        values.add(header.substring(start).trim());
        return values;
    }

    private ParsedLink parseLink(String value) {
        int open = value.indexOf('<');
        int close = value.indexOf('>', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(value.substring(open + 1, close));
        } catch (IllegalArgumentException exception) {
            throw invalidLink();
        }
        List<String> relations = new ArrayList<>();
        String parameters = value.substring(close + 1);
        for (String parameter : parameters.split(";")) {
            String trimmed = parameter.trim();
            int equals = trimmed.indexOf('=');
            if (equals <= 0 || !"rel".equalsIgnoreCase(trimmed.substring(0, equals).trim())) {
                continue;
            }
            String relationValue = trimmed.substring(equals + 1).trim();
            if (relationValue.length() >= 2
                    && relationValue.startsWith("\"")
                    && relationValue.endsWith("\"")) {
                relationValue = relationValue.substring(1, relationValue.length() - 1);
            }
            for (String relation : relationValue.split("\\s+")) {
                if (!relation.isBlank()) {
                    relations.add(relation.toLowerCase(Locale.ROOT));
                }
            }
        }
        return new ParsedLink(uri, relations);
    }

    private GitHubApiException invalidLink() {
        return new GitHubApiException(
                GitHubApiFailureType.MALFORMED_RESPONSE,
                false,
                null,
                null,
                "GitHub API returned an invalid Link header",
                null,
                null
        );
    }

    private record ParsedLink(URI uri, List<String> relations) {
    }
}

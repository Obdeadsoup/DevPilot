package com.obdeadsoup.devpilot.knowledge.retrieval;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public final class ConversationAwareQueryRewriter {
    private static final Set<String> CONTEXT_MARKERS = Set.of(
            "那", "这个", "它", "上述", "继续", "失败后", "怎么办", "then", "that", "it", "those", "continue"
    );

    public String rewrite(String query, List<String> history) {
        String normalized = query.strip();
        if (history == null || history.isEmpty() || !needsContext(normalized)) return normalized;
        for (int index = history.size() - 1; index >= 0; index--) {
            String prior = history.get(index);
            if (prior != null && !prior.isBlank()) {
                String bounded = prior.strip();
                if (bounded.length() > 600) bounded = bounded.substring(0, 600);
                return bounded + "\nFollow-up question: " + normalized;
            }
        }
        return normalized;
    }

    private boolean needsContext(String query) {
        if (query.length() <= 24) return true;
        String lower = query.toLowerCase(Locale.ROOT);
        return CONTEXT_MARKERS.stream().anyMatch(lower::contains);
    }
}

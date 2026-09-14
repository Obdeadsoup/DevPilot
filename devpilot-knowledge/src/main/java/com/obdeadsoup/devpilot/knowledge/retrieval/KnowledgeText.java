package com.obdeadsoup.devpilot.knowledge.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class KnowledgeText {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_./:-]+");

    private KnowledgeText() {}

    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            tokens.add(token);
            if (containsCjk(token) && token.length() > 1) {
                for (int i = 0; i < token.length() - 1; i++) tokens.add(token.substring(i, i + 2));
            }
        }
        return tokens;
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(code -> Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN);
    }
}

package com.obdeadsoup.devpilot.github.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Issue/PR/Review 外部文本的统一安全边界。内容仍是不可信数据；这里仅做持久化限长、
 * 稳定 JSON 和 GitHub HTTPS URL 校验，前端仍必须使用安全 Markdown Renderer。
 */
@Component
public class GitHubExternalContentPolicy {

    private static final int JSON_LIMIT = 4_000;
    private final ObjectMapper objectMapper;

    public GitHubExternalContentPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String title(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        return truncate(value, 512);
    }

    public String body(String value) {
        return truncate(value, 10_000);
    }

    public String login(String value) {
        return truncate(value, 100);
    }

    public String ref(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        return truncate(value, 255);
    }

    public String sha(String value, boolean nullable) {
        if (nullable && (value == null || value.isBlank())) {
            return null;
        }
        if (value == null || !value.matches("[0-9a-fA-F]{40}")) {
            throw invalid();
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public String githubUrl(String value) {
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null) {
                throw invalid();
            }
            return truncate(uri.toString(), 500);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    /** 将外部数组裁剪为稳定、排序后的合法 JSON；不会在字符串中间截断 JSON。 */
    public String summaryJson(String rawJson) {
        try {
            JsonNode parsed = rawJson == null ? objectMapper.createArrayNode() : objectMapper.readTree(rawJson);
            if (!parsed.isArray()) {
                throw invalid();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : parsed) {
                String value = item.isTextual() ? item.textValue() : item.path("login").asText(null);
                if (value == null) {
                    value = item.path("name").asText(null);
                }
                if (value != null && !value.isBlank()) {
                    values.add(truncate(value, 255));
                }
            }
            values.sort(Comparator.naturalOrder());
            ArrayNode result = objectMapper.createArrayNode();
            for (String value : values.stream().distinct().toList()) {
                result.add(value);
                if (objectMapper.writeValueAsString(result).length() > JSON_LIMIT) {
                    result.remove(result.size() - 1);
                    break;
                }
            }
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw invalid();
        }
    }

    /** 内容 Hash 不是签名；它只为同一 github_updated_at 下的快照幂等和差异检测服务。 */
    public String contentHash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("devpilot-snapshot-v1".getBytes(StandardCharsets.UTF_8));
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private BusinessException invalid() {
        return new BusinessException(GitHubSyncErrorCode.SNAPSHOT_RESPONSE_INVALID);
    }
}

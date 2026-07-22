package com.obdeadsoup.devpilot.project.api.dto;

import java.util.List;

public record ProjectActivityPageResponse(
        List<ProjectActivityResponse> items,
        int page,
        int size,
        long total,
        long totalPages
) {
}

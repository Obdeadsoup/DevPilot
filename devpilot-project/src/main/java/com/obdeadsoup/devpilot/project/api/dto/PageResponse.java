package com.obdeadsoup.devpilot.project.api.dto;

import java.util.List;

public record PageResponse<T>(
        int page,
        int size,
        long total,
        List<T> items
) {

    public PageResponse {
        items = List.copyOf(items);
    }
}

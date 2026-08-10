package com.obdeadsoup.devpilot.audit.domain;

import java.util.List;

public record PageResponse<T>(List<T> items, long total, int page, int size) {
}

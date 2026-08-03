package com.obdeadsoup.devpilot.github.api.dto;

import java.util.List;

public record GitHubSnapshotPageResponse<T>(List<T> items,int page,int size,long total,long totalPages){}

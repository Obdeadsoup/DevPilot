package com.obdeadsoup.devpilot.github.api.dto;

public record GitHubWebhookReceiptResponse(
        String deliveryId,
        String status,
        boolean duplicate
) {
}

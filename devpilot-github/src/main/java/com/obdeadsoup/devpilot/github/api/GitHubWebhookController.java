package com.obdeadsoup.devpilot.github.api;

import com.obdeadsoup.devpilot.framework.api.ApiResponse;
import com.obdeadsoup.devpilot.github.api.dto.GitHubWebhookReceiptResponse;
import com.obdeadsoup.devpilot.github.application.GitHubWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/github/webhooks")
public class GitHubWebhookController {

    private final GitHubWebhookService webhookService;

    public GitHubWebhookController(GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GitHubWebhookReceiptResponse>> receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String deliveryId,
            @RequestHeader(name = "X-GitHub-Event", required = false) String event
    ) {
        GitHubWebhookReceiptResponse receipt = webhookService.receive(rawBody, signature, deliveryId, event);
        HttpStatus status = receipt.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(ApiResponse.success(receipt));
    }
}

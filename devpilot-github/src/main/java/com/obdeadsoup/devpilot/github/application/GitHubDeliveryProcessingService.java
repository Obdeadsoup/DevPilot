package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.parser.GitHubWebhookPayloadParser;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
public class GitHubDeliveryProcessingService {

    private final GitHubWebhookPayloadParser payloadParser;
    private final ProjectActivityService activityService;
    private final GitHubDeliveryMapper deliveryMapper;
    private final Clock clock;

    public GitHubDeliveryProcessingService(
            GitHubWebhookPayloadParser payloadParser,
            ProjectActivityService activityService,
            GitHubDeliveryMapper deliveryMapper,
            Clock clock
    ) {
        this.payloadParser = payloadParser;
        this.activityService = activityService;
        this.deliveryMapper = deliveryMapper;
        this.clock = clock;
    }

    @Transactional
    public void process(GitHubDeliveryEntity delivery) {
        activityService.recordGitHubActivity(payloadParser.parse(delivery));
        int updated = deliveryMapper.markSucceeded(delivery.id(), delivery.version(), LocalDateTime.now(clock));
        if (updated != 1) {
            throw new BusinessException(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT);
        }
    }
}

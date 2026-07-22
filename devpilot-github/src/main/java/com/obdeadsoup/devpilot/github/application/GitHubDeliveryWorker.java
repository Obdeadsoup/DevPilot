package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class GitHubDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubDeliveryWorker.class);

    private final GitHubDeliveryStateService stateService;
    private final GitHubDeliveryProcessingService processingService;

    public GitHubDeliveryWorker(
            GitHubDeliveryStateService stateService,
            GitHubDeliveryProcessingService processingService
    ) {
        this.stateService = stateService;
        this.processingService = processingService;
    }

    public void process(long deliveryId) {
        Optional<GitHubDeliveryEntity> claimed = stateService.claim(deliveryId);
        if (claimed.isEmpty()) {
            return;
        }
        try {
            processingService.process(claimed.get());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "GitHub delivery processing failed deliveryId={} exceptionType={}",
                    deliveryId,
                    exception.getClass().getName()
            );
            stateService.markFailed(deliveryId);
        }
    }
}

package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.event.GitHubDeliveryReceivedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class GitHubDeliveryEventListener {

    private final GitHubDeliveryWorker worker;

    public GitHubDeliveryEventListener(GitHubDeliveryWorker worker) {
        this.worker = worker;
    }

    @Async("githubDeliveryTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeliveryReceived(GitHubDeliveryReceivedEvent event) {
        worker.process(event.deliveryId());
    }
}

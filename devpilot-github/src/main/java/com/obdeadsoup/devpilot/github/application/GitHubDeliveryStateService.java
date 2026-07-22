package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class GitHubDeliveryStateService {

    private final GitHubDeliveryMapper deliveryMapper;
    private final Clock clock;

    public GitHubDeliveryStateService(GitHubDeliveryMapper deliveryMapper, Clock clock) {
        this.deliveryMapper = deliveryMapper;
        this.clock = clock;
    }

    @Transactional
    public Optional<GitHubDeliveryEntity> claim(long deliveryId) {
        Optional<GitHubDeliveryEntity> candidate = deliveryMapper.findById(deliveryId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        GitHubDeliveryEntity delivery = candidate.get();
        int claimed = deliveryMapper.claim(delivery.id(), delivery.version(), LocalDateTime.now(clock));
        if (claimed != 1) {
            return Optional.empty();
        }
        return deliveryMapper.findById(deliveryId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long deliveryId) {
        deliveryMapper.markFailed(
                deliveryId,
                "PROCESSING_ERROR",
                "Delivery processing failed",
                LocalDateTime.now(clock)
        );
    }
}

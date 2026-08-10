package com.obdeadsoup.devpilot.audit.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditDomainTest {
    @Test void exposesOnlyControlledActionsAndResources(){
        assertThat(AuditActionType.values()).containsExactlyInAnyOrder(
                AuditActionType.OUTBOX_REPLAY_REQUESTED,AuditActionType.OUTBOX_REPLAY_CREATED,
                AuditActionType.OUTBOX_REPLAY_REJECTED,AuditActionType.GITHUB_SYNC_REPLAY_REQUESTED,
                AuditActionType.GITHUB_SYNC_REPLAY_CREATED,AuditActionType.GITHUB_SYNC_REPLAY_REJECTED,
                AuditActionType.OUTBOX_DEAD_VIEWED,AuditActionType.GITHUB_SYNC_DEAD_VIEWED);
        assertThat(AuditResourceType.values()).containsExactlyInAnyOrder(
                AuditResourceType.OUTBOX_EVENT,AuditResourceType.GITHUB_SYNC_RUN,
                AuditResourceType.GITHUB_REPOSITORY_BINDING);
        assertThat(AuditResult.values()).containsExactly(AuditResult.SUCCESS,AuditResult.FAILURE,AuditResult.DENIED);
    }
}

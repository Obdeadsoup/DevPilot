package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 只允许已审查的 Task V1 事件人工重放；未知类型默认拒绝，且任何非 DEAD 状态都不能被“复活”。 */
@Component
public class ReplayableOutboxEventPolicy {
    private static final Set<String> ALLOWED = Set.of(
            "TASK_ASSIGNED_V1", "TASK_UNASSIGNED_V1", "TASK_SUBMITTED_FOR_REVIEW_V1",
            "TASK_CHANGES_REQUESTED_V1", "TASK_COMPLETED_V1", "TASK_REOPENED_V1");

    public void requireReplayable(String status, String eventType) {
        if (!"DEAD".equals(status) || !ALLOWED.contains(eventType)) {
            throw new BusinessException(AuditErrorCode.DEAD_EVENT_NOT_REPLAYABLE);
        }
    }
}

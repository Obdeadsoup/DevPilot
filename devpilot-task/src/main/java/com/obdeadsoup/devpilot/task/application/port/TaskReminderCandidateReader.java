package com.obdeadsoup.devpilot.task.application.port;

import java.time.LocalDateTime;
import java.util.List;

/** 向末端提醒模块暴露有界、中立的 Task 候选，不泄露 Task Mapper。扫描允许重复发现同一事实。 */
public interface TaskReminderCandidateReader {
    List<TaskReminderCandidate> findDueSoon(LocalDateTime now, LocalDateTime upperBound, int limit);
    List<TaskReminderCandidate> findOverdue(LocalDateTime now, int limit);
    List<TaskReminderCandidate> findOverdueForEscalation(LocalDateTime cutoff, int limit);
    /** Review 起点来自最近一次 SUBMITTED_FOR_REVIEW History，而不是会被普通编辑改变的 updated_at。 */
    List<TaskReminderCandidate> findReviewTimeout(LocalDateTime cutoff, int limit);
}

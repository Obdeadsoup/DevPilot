package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.task.application.port.TaskReminderCandidate;
import com.obdeadsoup.devpilot.task.application.port.TaskReminderCandidateReader;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.List;

/** 在 Task 模块内实现提醒候选查询，确保终态、逻辑删除、History 与批次规则不外泄。 */
@Service
public class TaskReminderCandidateService implements TaskReminderCandidateReader {
    private final TaskMapper mapper;
    public TaskReminderCandidateService(TaskMapper mapper) { this.mapper = mapper; }
    public List<TaskReminderCandidate> findDueSoon(LocalDateTime n, LocalDateTime u, int l){ return mapper.findReminderCandidates("DUE",n,u,l); }
    public List<TaskReminderCandidate> findOverdue(LocalDateTime n,int l){ return mapper.findReminderCandidates("OVERDUE",n,n,l); }
    public List<TaskReminderCandidate> findOverdueForEscalation(LocalDateTime c,int l){ return mapper.findReminderCandidates("ESCALATION",c,c,l); }
    public List<TaskReminderCandidate> findReviewTimeout(LocalDateTime c,int l){ return mapper.findReminderCandidates("REVIEW",c,c,l); }
}

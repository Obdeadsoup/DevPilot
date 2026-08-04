package com.obdeadsoup.devpilot.task.domain;

import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 本地 Task 的纯状态矩阵。Task 不是 GitHub Issue：外部快照只能提示协作上下文，
 * 不能越过本地动作、权限和乐观锁直接改变 Task 状态。
 */
@Component
public class TaskTransitionPolicy {

    public TaskTransition create() {
        return new TaskTransition(null, TaskStatus.BACKLOG, TaskAction.CREATED, false, false);
    }

    /** 按显式领域动作验证唯一允许的前置状态与目标状态。 */
    public TaskTransition transition(TaskStatus current, TaskAction action) {
        return switch (action) {
            case PLANNED -> require(current, TaskStatus.BACKLOG, TaskStatus.TODO, action);
            case RETURNED_TO_BACKLOG -> require(current, TaskStatus.TODO, TaskStatus.BACKLOG, action);
            case STARTED -> require(current, TaskStatus.TODO, TaskStatus.IN_PROGRESS, action);
            case SUBMITTED_FOR_REVIEW -> require(current, TaskStatus.IN_PROGRESS, TaskStatus.IN_REVIEW, action);
            case CHANGES_REQUESTED -> require(current, TaskStatus.IN_REVIEW, TaskStatus.IN_PROGRESS, action);
            case COMPLETED -> require(current, TaskStatus.IN_REVIEW, TaskStatus.DONE, action);
            case CANCELED -> {
                if (current == TaskStatus.BACKLOG || current == TaskStatus.TODO
                        || current == TaskStatus.IN_PROGRESS || current == TaskStatus.IN_REVIEW) {
                    yield new TaskTransition(current, TaskStatus.CANCELED, action, false, true);
                }
                throw invalid();
            }
            case REOPENED -> {
                if (current == TaskStatus.DONE || current == TaskStatus.CANCELED) {
                    yield new TaskTransition(current, TaskStatus.TODO, action, false, false);
                }
                throw invalid();
            }
            case CREATED -> throw invalid();
        };
    }

    private TaskTransition require(TaskStatus current, TaskStatus expected, TaskStatus target, TaskAction action) {
        if (current != expected) {
            throw invalid();
        }
        return new TaskTransition(current, target, action, target == TaskStatus.DONE, target == TaskStatus.CANCELED);
    }

    private BusinessException invalid() {
        return new BusinessException(TaskErrorCode.TASK_INVALID_TRANSITION);
    }
}

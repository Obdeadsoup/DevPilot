package com.obdeadsoup.devpilot.task.domain;

/** 状态动作的固定结果；持久化层据此同步维护终态时间，而不是让调用方传入目标状态。 */
public record TaskTransition(TaskStatus from, TaskStatus to, TaskAction action, boolean completes, boolean cancels) {
}

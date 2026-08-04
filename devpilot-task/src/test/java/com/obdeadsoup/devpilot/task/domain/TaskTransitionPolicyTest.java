package com.obdeadsoup.devpilot.task.domain;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskTransitionPolicyTest {
    private final TaskTransitionPolicy policy = new TaskTransitionPolicy();

    @Test void supportsCompleteMainlineAndClearsTerminalFlagsUntilDone() {
        assertThat(policy.create()).extracting(TaskTransition::to,TaskTransition::action).containsExactly(TaskStatus.BACKLOG,TaskAction.CREATED);
        assertTransition(TaskStatus.BACKLOG,TaskAction.PLANNED,TaskStatus.TODO,false,false);
        assertTransition(TaskStatus.TODO,TaskAction.STARTED,TaskStatus.IN_PROGRESS,false,false);
        assertTransition(TaskStatus.IN_PROGRESS,TaskAction.SUBMITTED_FOR_REVIEW,TaskStatus.IN_REVIEW,false,false);
        assertTransition(TaskStatus.IN_REVIEW,TaskAction.COMPLETED,TaskStatus.DONE,true,false);
    }

    @Test void supportsChangesCancelAndReopenMatrix() {
        assertTransition(TaskStatus.IN_REVIEW,TaskAction.CHANGES_REQUESTED,TaskStatus.IN_PROGRESS,false,false);
        for (TaskStatus status : List.of(TaskStatus.BACKLOG,TaskStatus.TODO,TaskStatus.IN_PROGRESS,TaskStatus.IN_REVIEW)) assertTransition(status,TaskAction.CANCELED,TaskStatus.CANCELED,false,true);
        assertTransition(TaskStatus.DONE,TaskAction.REOPENED,TaskStatus.TODO,false,false);
        assertTransition(TaskStatus.CANCELED,TaskAction.REOPENED,TaskStatus.TODO,false,false);
        assertTransition(TaskStatus.TODO,TaskAction.RETURNED_TO_BACKLOG,TaskStatus.BACKLOG,false,false);
    }

    @Test void rejectsEveryUndefinedTransitionIncludingTerminalStates() {
        for (TaskStatus status : TaskStatus.values()) for (TaskAction action : TaskAction.values()) {
            boolean allowed = (status==TaskStatus.BACKLOG&&List.of(TaskAction.PLANNED,TaskAction.CANCELED).contains(action))
                    ||(status==TaskStatus.TODO&&List.of(TaskAction.RETURNED_TO_BACKLOG,TaskAction.STARTED,TaskAction.CANCELED).contains(action))
                    ||(status==TaskStatus.IN_PROGRESS&&List.of(TaskAction.SUBMITTED_FOR_REVIEW,TaskAction.CANCELED).contains(action))
                    ||(status==TaskStatus.IN_REVIEW&&List.of(TaskAction.CHANGES_REQUESTED,TaskAction.COMPLETED,TaskAction.CANCELED).contains(action))
                    ||((status==TaskStatus.DONE||status==TaskStatus.CANCELED)&&action==TaskAction.REOPENED);
            if (!allowed) assertThatThrownBy(()->policy.transition(status,action)).isInstanceOf(BusinessException.class);
        }
    }
    private void assertTransition(TaskStatus from,TaskAction action,TaskStatus to,boolean completes,boolean cancels){
        assertThat(policy.transition(from,action)).isEqualTo(new TaskTransition(from,to,action,completes,cancels));
    }
}

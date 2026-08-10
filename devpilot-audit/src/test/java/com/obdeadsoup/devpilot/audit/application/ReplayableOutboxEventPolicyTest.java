package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReplayableOutboxEventPolicyTest {
    private final ReplayableOutboxEventPolicy policy=new ReplayableOutboxEventPolicy();
    @Test void allowsWhitelistedDead(){assertThatCode(()->policy.requireReplayable("DEAD","TASK_COMPLETED_V1")).doesNotThrowAnyException();}
    @Test void rejectsEveryNonDeadStatus(){
        for(String status:new String[]{"PROCESSED","PENDING","PROCESSING","RETRY_WAIT"})
            assertThatThrownBy(()->policy.requireReplayable(status,"TASK_COMPLETED_V1")).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsUnknownEventType(){assertThatThrownBy(()->policy.requireReplayable("DEAD","UNKNOWN_V1")).isInstanceOf(BusinessException.class);}
}

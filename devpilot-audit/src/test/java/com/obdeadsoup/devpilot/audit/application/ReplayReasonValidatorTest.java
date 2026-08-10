package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReplayReasonValidatorTest {
    private final ReplayReasonValidator validator=new ReplayReasonValidator();
    @Test void trimsValidReason(){assertThat(validator.validate("  已修复处理器兼容问题，现在重新执行。  ")).isEqualTo("已修复处理器兼容问题，现在重新执行。");}
    @Test void rejectsShortMeaninglessAndTooLongReasons(){
        assertThatThrownBy(()->validator.validate("retry")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->validator.validate(".")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->validator.validate("x".repeat(501))).isInstanceOf(BusinessException.class);
    }
}

package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.springframework.stereotype.Component;

/** 人工 Replay 必须给出可供事后复盘的理由；仅有 retry、test 或标点不构成有效运维依据。 */
@Component
public class ReplayReasonValidator {
    public String validate(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.length() < 10 || normalized.length() > 500
                || normalized.matches("(?i)retry|test") || normalized.matches("[\\p{Punct}\\s]+")) {
            throw new BusinessException(AuditErrorCode.INVALID_REPLAY_REASON);
        }
        return normalized;
    }
}

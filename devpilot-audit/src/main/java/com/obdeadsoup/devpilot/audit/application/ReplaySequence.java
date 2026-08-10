package com.obdeadsoup.devpilot.audit.application;

import org.springframework.stereotype.Component;

@Component
public class ReplaySequence {
    public int next(int currentMaximum) {
        if (currentMaximum < 0 || currentMaximum == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Replay sequence cannot advance");
        }
        return currentMaximum + 1;
    }
}

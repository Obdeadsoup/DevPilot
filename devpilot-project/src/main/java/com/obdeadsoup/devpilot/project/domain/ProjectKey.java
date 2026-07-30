package com.obdeadsoup.devpilot.project.domain;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

public record ProjectKey(String value) {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Z][A-Z0-9]{1,11}");

    public ProjectKey {
        if (value == null || !VALID_KEY.matcher(value).matches()) {
            throw new BusinessException(ProjectErrorCode.INVALID_PROJECT_KEY);
        }
    }

    public static ProjectKey from(String rawValue) {
        if (rawValue == null) {
            throw new BusinessException(ProjectErrorCode.INVALID_PROJECT_KEY);
        }
        return new ProjectKey(rawValue.strip().toUpperCase(Locale.ROOT));
    }
}

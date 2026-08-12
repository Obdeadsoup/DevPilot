package com.obdeadsoup.devpilot.project.application.port;

/** Task 模块所需的最小 Project 上下文，不泄露 Project Persistence Entity。 */
public record ProjectTaskContext(String projectKey, boolean archived, boolean activeScope) {
}

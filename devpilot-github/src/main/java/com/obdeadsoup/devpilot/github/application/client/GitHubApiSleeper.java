package com.obdeadsoup.devpilot.github.application.client;

import java.time.Duration;

/** 可替换的 Retry 等待边界，测试可注入记录器而不使用 Thread.sleep。 */
@FunctionalInterface
public interface GitHubApiSleeper {

    void sleep(Duration duration) throws InterruptedException;
}

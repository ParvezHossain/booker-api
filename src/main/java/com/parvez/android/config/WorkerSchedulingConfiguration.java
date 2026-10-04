package com.parvez.android.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Preserve a default worker scheduler independently of either email feature flag. */
@Configuration(proxyBeanMethods = false)
public class WorkerSchedulingConfiguration {
    @Bean ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("application-worker-");
        return scheduler;
    }
}

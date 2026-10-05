package com.docbase.chat.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.ThreadPoolExecutor;

/** Isolates blocking SSE writes and completion persistence from WebClient event loops. */
@Configuration(proxyBeanMethods = false)
public class ChatExecutorConfig {

    @Bean("chatStreamExecutor")
    public ThreadPoolTaskExecutor chatStreamExecutor(
            @Value("${docbase.executors.chat-stream.core-size:4}") int coreSize,
            @Value("${docbase.executors.chat-stream.max-size:8}") int maxSize,
            @Value("${docbase.executors.chat-stream.queue-capacity:128}") int queueCapacity,
            @Value("${docbase.executors.chat-stream.keep-alive-seconds:60}") int keepAliveSeconds) {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 1 || keepAliveSeconds < 1) {
            throw new IllegalArgumentException("Invalid chat stream executor limits");
        }
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix("chat-stream-");
        // Never run blocking work on the submitting Netty thread under saturation.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }

    @Bean(name = "chatStreamScheduler", destroyMethod = "dispose")
    public Scheduler chatStreamScheduler(
            @Qualifier("chatStreamExecutor") ThreadPoolTaskExecutor executor) {
        return Schedulers.fromExecutor(executor);
    }
}

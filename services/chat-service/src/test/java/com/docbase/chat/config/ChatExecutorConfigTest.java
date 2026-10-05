package com.docbase.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatExecutorConfigTest {
    private final ChatExecutorConfig config = new ChatExecutorConfig();

    @Test
    void streamCallbacksUseNamedWorkerAndPreserveOrder() {
        ThreadPoolTaskExecutor executor = config.chatStreamExecutor(1, 2, 4, 60);
        executor.initialize();
        Scheduler scheduler = config.chatStreamScheduler(executor);
        try {
            assertThat(Flux.range(1, 3).publishOn(scheduler, 2)
                    .map(value -> Thread.currentThread().getName() + ":" + value)
                    .collectList().block(Duration.ofSeconds(5)))
                    .containsExactly("chat-stream-1:1", "chat-stream-1:2", "chat-stream-1:3");
        } finally {
            scheduler.dispose();
            executor.shutdown();
        }
    }

    @Test
    void boundedQueueRejectsInsteadOfRunningOnCaller() throws Exception {
        ThreadPoolTaskExecutor executor = config.chatStreamExecutor(1, 1, 1, 60);
        executor.initialize();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> {});
            assertThatThrownBy(() -> executor.execute(() -> {
                throw new AssertionError("Rejected work must not run on the caller");
            })).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void invalidLimitsFailAtStartup() {
        assertThatThrownBy(() -> config.chatStreamExecutor(4, 2, 128, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.chatStreamExecutor(1, 2, 0, 60))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

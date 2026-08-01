package com.vrudenko.kanban_board.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables {@code @Async} and provides the bounded thread pool {@link
 * com.vrudenko.kanban_board.config.KafkaEventPublisher} dispatches onto, so a Kafka publish never
 * runs on the caller's thread (HTTP request thread in production, test fixture-setup thread in
 * tests) regardless of broker reachability.
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "kafkaPublishExecutor")
    public TaskExecutor kafkaPublishExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("kafka-publish-");
        executor.initialize();
        return executor;
    }
}

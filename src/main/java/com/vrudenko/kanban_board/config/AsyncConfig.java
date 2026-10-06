package com.vrudenko.kanban_board.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enable @Async and provide the bounded pool KafkaEventPublisher dispatches onto,
 * so a Kafka publish never runs on the caller's thread whatever the broker's reachability.
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

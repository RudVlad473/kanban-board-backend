package com.vrudenko.kanban_board.config;

import java.util.LinkedHashMap;

import com.vrudenko.kanban_board.constant.KafkaTopics;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Provision both {@code activitylog} Kafka topics and wire the retry-then-dead-letter handling that
 * isolates a poison message from the rest of the feed.
 *
 * <p>A duplicate {@code eventId} never reaches the error handler: {@link
 * com.vrudenko.kanban_board.activitylog.ActivityLogRecorder} completes normally on both its fast
 * path and its constraint backstop, so only a genuine failure (malformed payload, down database,
 * serialisation error) propagates out of the listener. Dead-lettering a deserialization failure
 * requires the {@code ErrorHandlingDeserializer} block in {@code application.properties}; without
 * it a malformed payload fails inside the poll loop before this handler sees the record.
 */
@Configuration
public class KafkaConsumerConfig implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    private DefaultKafkaProducerFactory<String, Object> deadLetterProducerFactory;

    @Bean
    public NewTopic activityTopic() {
        return org.springframework.kafka.config.TopicBuilder.name(KafkaTopics.ACTIVITY)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic activityDeadLetterTopic() {
        return org.springframework.kafka.config.TopicBuilder.name(KafkaTopics.ACTIVITY_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    /**
     * Declare the {@code @Primary} default template so unqualified injection does not resolve to
     * the dead-letter template.
     *
     * <p>Decisions:
     *
     * <p>Any {@code @Bean} of type {@code KafkaTemplate} disables Spring Boot's {@code
     * KafkaAutoConfiguration.kafkaTemplate()} outright: it is guarded by a bare-type
     * {@code @ConditionalOnMissingBean(KafkaTemplate.class)}, blind to generic parameterisation. So
     * {@link #deadLetterKafkaTemplate} alone suppressed it, and every unqualified {@code @Autowired
     * KafkaTemplate<String, Object>} (including {@link KafkaEventPublisher}) silently resolved to
     * the DLT-flavoured template, which built its producer properties from {@code KafkaProperties}
     * and so ignored a {@code KafkaConnectionDetails} override (e.g. Testcontainers'
     * {@code @ServiceConnection}). {@link #deadLetterKafkaTemplate} stays reachable only by bean
     * name or {@code @Qualifier}.
     */
    @Bean
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }

    /**
     * Build a template whose serializer preserves a dead-lettered {@code byte[]} record value,
     * which the application's JSON-valued template would base64-encode.
     *
     * <p>Producer properties come from the autoconfigured {@code kafkaProducerFactory} bean, so
     * this template inherits its bootstrap servers (including any {@code KafkaConnectionDetails}
     * override) and timeouts instead of a ConnectionDetails-blind copy built from {@code
     * KafkaProperties}.
     *
     * <p>Decisions:
     *
     * <p>{@code delegates} must be a {@link LinkedHashMap}: {@link DelegatingByTypeSerializer} with
     * {@code assumeType=true} walks iteration order and uses the first entry whose key {@code
     * isAssignableFrom} the value's class, and {@code Object.class} matches {@code byte[]}. With a
     * {@code HashMap} a dead-lettered payload could hit {@code Object.class} first, go through
     * {@link JsonSerializer} and be base64-encoded. This was a production bug, surfaced only by
     * live Testcontainers verification of the real dead-letter path; {@code HashMap} iteration
     * order is invisible to {@code compileJava} and to mocked tests.
     *
     * <p>The producer factory is deliberately not its own {@code @Bean}: {@code
     * KafkaAutoConfiguration.kafkaProducerFactory()} is guarded by a bare-type
     * {@code @ConditionalOnMissingBean(ProducerFactory.class)}, so a second {@code ProducerFactory}
     * bean of any parameterisation would suppress it and its {@code KafkaConnectionDetails}
     * override. The reference is kept on this {@code @Configuration} and closed from {@link
     * #destroy()}.
     */
    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate(
            ProducerFactory<Object, Object> kafkaProducerFactory) {
        var delegates = new LinkedHashMap<Class<?>, Serializer<?>>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());

        this.deadLetterProducerFactory =
                new DefaultKafkaProducerFactory<>(
                        kafkaProducerFactory.getConfigurationProperties(),
                        new StringSerializer(),
                        new DelegatingByTypeSerializer(delegates, true));
        return new KafkaTemplate<>(this.deadLetterProducerFactory);
    }

    /**
     * Close the dead-letter producer factory, which is not a bean (see {@link
     * #deadLetterKafkaTemplate}).
     */
    @Override
    public void destroy() {
        if (deadLetterProducerFactory != null) {
            deadLetterProducerFactory.destroy();
        }
    }

    /**
     * Retry three times at a ~1s fixed interval, then dead-letter to {@link
     * KafkaTopics#ACTIVITY_DLT}.
     *
     * <p>Records are pinned to partition 0: the topic has one partition, so a source partition
     * number could not exist there. Every dead-lettering is logged at error level with source
     * topic, partition, offset and cause, so a draining feed is a log line, not silence.
     *
     * <p>Decisions:
     *
     * <p>{@code @Qualifier("deadLetterKafkaTemplate")} is required even though the parameter name
     * matches the bean name: Spring looks for a {@code @Primary} candidate <em>before</em> falling
     * back to parameter-name matching, so with {@link #kafkaTemplate} {@code @Primary} a bare
     * parameter silently resolved to the default JSON-valued template and always base64-encoded the
     * dead-lettered {@code byte[]}. Only live Testcontainers verification of the real dead-letter
     * path caught it; {@code compileJava} cannot see which bean an ambiguous autowire point
     * resolves to at runtime.
     */
    @Bean
    public DefaultErrorHandler activityErrorHandler(
            @Qualifier("deadLetterKafkaTemplate") KafkaTemplate<String, Object> deadLetterKafkaTemplate) {
        var recoverer =
                new DeadLetterPublishingRecoverer(
                        deadLetterKafkaTemplate,
                        (record, ex) -> new TopicPartition(KafkaTopics.ACTIVITY_DLT, 0));

        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        errorHandler.setRetryListeners(
                new RetryListener() {
                    @Override
                    public void failedDelivery(
                            ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                        // No-op: DefaultErrorHandler already logs each retry at WARN, and
                        // recovered() covers the final dead-lettering.
                    }

                    @Override
                    public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
                        log.error(
                                "Dead-lettering record to {} after exhausting retries:"
                                        + " sourceTopic={} partition={} offset={} cause={}",
                                KafkaTopics.ACTIVITY_DLT,
                                record.topic(),
                                record.partition(),
                                record.offset(),
                                ex.getMessage(),
                                ex);
                    }
                });
        return errorHandler;
    }
}

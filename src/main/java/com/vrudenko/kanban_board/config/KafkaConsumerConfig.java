package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import java.util.HashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Provisions both {@code activitylog} Kafka topics explicitly (RELY-01, D-07, D-08) and wires the
 * retry-then-dead-letter machinery that isolates a poison message from the rest of the feed.
 *
 * <p>A duplicate {@code eventId} never reaches this error handler by design: {@link
 * com.vrudenko.kanban_board.activitylog.ActivityLogRecorder} completes normally on both its fast
 * path and its constraint backstop, so only a genuine failure (a malformed payload, a down
 * database, a serialisation error) ever propagates out of the listener method and reaches {@link
 * DefaultErrorHandler}. The deserialization-failure path this handler depends on requires the
 * {@code ErrorHandlingDeserializer} property block in {@code application.properties}: without it a
 * malformed payload fails inside the poll loop before this handler ever sees the record.
 */
@Configuration
public class KafkaConsumerConfig {
    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

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
     * A dead-lettered deserialization failure carries the raw {@code byte[]} as its record value.
     * Routing it through the application's own JSON-valued producer template would base64-encode
     * those bytes, destroying the one artefact an operator actually needs to inspect — so this
     * template gets its own byte-preserving delegating serializer instead. Producer bounds are
     * sourced from {@link KafkaProperties} so this template inherits the same bootstrap servers and
     * timeouts as the application's existing producer rather than hard-coding a second copy.
     */
    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate(KafkaProperties kafkaProperties) {
        var delegates = new HashMap<Class<?>, Serializer<?>>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());

        var producerFactory =
                new DefaultKafkaProducerFactory<String, Object>(
                        kafkaProperties.buildProducerProperties(),
                        new StringSerializer(),
                        new DelegatingByTypeSerializer(delegates, true));
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Three retries at a ~1s fixed interval (D-04's literal reading of "3 retries") before routing
     * to {@link KafkaTopics#ACTIVITY_DLT}, pinned to partition 0 since the dead-letter topic has
     * exactly one partition and inheriting a source partition number the target topic does not have
     * would be wrong. Every recovery (i.e. every dead-lettering) is logged at error level naming
     * the source topic, partition, offset and cause, so a draining feed shows up as a log line
     * instead of silence.
     */
    @Bean
    public DefaultErrorHandler activityErrorHandler(
            KafkaTemplate<String, Object> deadLetterKafkaTemplate) {
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
                        // DefaultErrorHandler already logs each individual retry attempt at WARN;
                        // the additional signal this phase requires is on final dead-lettering,
                        // handled by recovered() below.
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

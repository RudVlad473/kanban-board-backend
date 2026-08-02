package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import java.util.LinkedHashMap;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * Any {@code @Bean} of type {@code KafkaTemplate} anywhere in the app disables Spring Boot's
     * autoconfigured {@code KafkaAutoConfiguration.kafkaTemplate()} bean outright — it is guarded
     * by a bare-type {@code @ConditionalOnMissingBean(KafkaTemplate.class)}, which does not
     * distinguish between generic parameterisations, so {@link #deadLetterKafkaTemplate} alone was
     * enough to suppress it. Every unqualified {@code @Autowired KafkaTemplate<String, Object>} in
     * the app (including {@link KafkaEventPublisher}) then silently resolved to the DLT-flavoured
     * template instead — which, before this bean existed, built its own producer properties
     * directly from {@code KafkaProperties} rather than the autoconfigured {@code ProducerFactory},
     * so it never picked up a {@code KafkaConnectionDetails} override (e.g. Testcontainers'
     * {@code @ServiceConnection}) the way the real default template does. This bean restores an
     * explicit, {@code @Primary} default template so unqualified injection sites get the correct
     * one again; {@link #deadLetterKafkaTemplate} remains reachable only by its bean name /
     * {@code @Qualifier}.
     */
    @Bean
    @Primary
    public KafkaTemplate<String, Object> kafkaTemplate(
            ProducerFactory<String, Object> kafkaProducerFactory) {
        return new KafkaTemplate<>(kafkaProducerFactory);
    }

    /**
     * A dead-lettered deserialization failure carries the raw {@code byte[]} as its record value.
     * Routing it through the application's own JSON-valued producer template would base64-encode
     * those bytes, destroying the one artefact an operator actually needs to inspect — so this
     * template gets its own byte-preserving delegating serializer instead. Its producer properties
     * are read from the autoconfigured {@code kafkaProducerFactory} bean (not rebuilt from {@code
     * KafkaProperties} directly) so this template inherits the same bootstrap servers — including
     * any {@code KafkaConnectionDetails} override — and timeouts as {@link #kafkaTemplate}, rather
     * than hard-coding a second, ConnectionDetails-blind copy.
     *
     * <p>{@code delegates} must be a {@link LinkedHashMap}, not a plain {@code HashMap}: {@link
     * DelegatingByTypeSerializer} constructed with {@code assumeType=true} walks the map's
     * iteration order and dispatches to the first entry whose key {@code isAssignableFrom} the
     * value's runtime class. {@code Object.class} is assignable from every type, including {@code
     * byte[]}, so with a plain {@code HashMap} (whose bucket order is not insertion order) every
     * dead-lettered {@code byte[]} payload could just as easily match {@code Object.class} first,
     * silently routing it through {@link JsonSerializer} and base64-encoding the very bytes this
     * template exists to preserve. This was a genuine production bug, not a test artifact: only
     * live Testcontainers verification against the real dead-letter path (Plan 02's reliability
     * suite) surfaced it, since {@code ./gradlew compileJava} and any mocked test cannot observe
     * {@code HashMap} iteration order. A {@link LinkedHashMap} makes the more-specific {@code
     * byte[].class} entry always win over the catch-all {@code Object.class} entry, regardless of
     * hashing.
     *
     * <p>The producer factory backing this template is its own {@code @Bean} ({@link
     * #deadLetterProducerFactory}) rather than a plain local variable: {@link
     * DefaultKafkaProducerFactory} implements {@code DisposableBean}/{@code SmartLifecycle}, and
     * only a container-managed bean gets its {@code destroy()}/{@code stop()} called on context
     * shutdown. A factory reachable only through the {@link KafkaTemplate} that wraps it would leak
     * its underlying producer (and its network connections/buffers) across context refreshes.
     */
    @Bean
    public ProducerFactory<String, Object> deadLetterProducerFactory(
            ProducerFactory<Object, Object> kafkaProducerFactory) {
        var delegates = new LinkedHashMap<Class<?>, Serializer<?>>();
        delegates.put(byte[].class, new ByteArraySerializer());
        delegates.put(Object.class, new JsonSerializer<>());

        return new DefaultKafkaProducerFactory<>(
                kafkaProducerFactory.getConfigurationProperties(),
                new StringSerializer(),
                new DelegatingByTypeSerializer(delegates, true));
    }

    @Bean
    public KafkaTemplate<String, Object> deadLetterKafkaTemplate(
            @Qualifier("deadLetterProducerFactory") ProducerFactory<String, Object> deadLetterProducerFactory) {
        return new KafkaTemplate<>(deadLetterProducerFactory);
    }

    /**
     * Three retries at a ~1s fixed interval (D-04's literal reading of "3 retries") before routing
     * to {@link KafkaTopics#ACTIVITY_DLT}, pinned to partition 0 since the dead-letter topic has
     * exactly one partition and inheriting a source partition number the target topic does not have
     * would be wrong. Every recovery (i.e. every dead-lettering) is logged at error level naming
     * the source topic, partition, offset and cause, so a draining feed shows up as a log line
     * instead of silence.
     *
     * <p>{@code @Qualifier("deadLetterKafkaTemplate")} is required on the parameter below, even
     * though its name already matches the bean name exactly. Spring's autowire-candidate resolution
     * checks for a {@code @Primary} bean among the candidates for a type <em>before</em> it ever
     * falls back to matching by parameter name -- with two {@code KafkaTemplate<String, Object>}
     * beans in this class and {@link #kafkaTemplate} marked {@code @Primary}, a bare, unqualified
     * parameter here silently resolved to the default JSON-valued template instead of {@link
     * #deadLetterKafkaTemplate}, regardless of the parameter's name. The dead-lettered {@code
     * byte[]} payload was then always JSON/base64-encoded by the wrong template, no matter how
     * correctly {@link #deadLetterKafkaTemplate}'s own delegating serializer was configured. Only
     * live Testcontainers verification against the real dead-letter path (Plan 02's reliability
     * suite) could have caught this: {@code ./gradlew compileJava} cannot see which bean an
     * ambiguous, unqualified autowire point actually resolves to at runtime.
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

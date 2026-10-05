package com.vrudenko.kanban_board.support.containers;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.vrudenko.kanban_board.config.AvroSchemaRegistrar;
import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.avro.ActivityEventAvroMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared broker-and-registry harness for the {@code activitylog} integration tests: one {@code
 * docker.redpanda.com/redpandadata/redpanda:v26.2.1} container.
 *
 * <p>The container exposes a Kafka broker and a Confluent-compatible Schema Registry.
 *
 * <p>Why this is the way it is:
 *
 * <ul>
 *   <li>Redpanda replaced {@code apache/kafka-native:4.3.1} in place, rather than adding a second
 *       Avro-specific harness: the pre-existing {@code activitylog} E2E classes never touch a
 *       registry, so they pass unchanged because Redpanda is a Kafka-protocol superset of what they
 *       exercised.
 *   <li>Producer bounds ({@code max.block.ms}, {@code request.timeout.ms}, {@code
 *       delivery.timeout.ms}) are raised to 30 seconds for this context only. The test profile
 *       bounds them at 50ms so a missing broker cannot slow the suite, but against a real broker
 *       50ms aborts the first send before the container finishes announcing itself. 30 seconds, not
 *       the original 10, matches the {@code Awaitility} ceiling: with several Testcontainers-backed
 *       classes sharing one broker and one {@code activity-log} consumer group, cumulative load in
 *       a full-suite run occasionally expired a 10-second bound ({@code TimeoutException: Expiring
 *       1 record(s)...}) though every class passes in isolation. It is headroom for real load, not
 *       a retry loop or a softened assertion, and does not affect what any test asserts.
 *   <li>It does not extend {@code AbstractAppTest}: the consumer path needs no user, board, column
 *       or task fixture, and {@code AbstractAppTest}'s setup creates roughly twenty entities
 *       through the real services, each publishing an event into the broker under test and turning
 *       every test into a race against unrelated traffic. It shares only {@link
 *       AbstractPostgresContainerTest}, the common ancestor, so one PostgreSQL container backs the
 *       whole suite.
 *   <li>The container starts imperatively in a static initializer ({@code kafka.start()}), not via
 *       the {@code @Testcontainers}/{@code @Container} extension. Observed on Windows with Docker
 *       Desktop and testcontainers-java 1.21.0: the extension's singleton-container pattern did not
 *       hold across this package's classes. A second container (different ID and mapped port)
 *       started for a later class while Spring's cached {@code ApplicationContext} kept its {@code
 *       KafkaTemplate}/{@code @KafkaListener} beans bound to the first container's stale port, and
 *       raw clients built via {@link #getBootstrapServers()} (evaluated on every call) reached the
 *       current container. Spring-mediated sends hung until timeout while raw clients worked. A
 *       static initializer runs exactly once per classloader, independent of JUnit extension
 *       bookkeeping.
 *   <li>{@code @ServiceConnection} on {@code kafka} wires {@code spring.kafka.bootstrap-servers}
 *       (Spring Boot 3.5.16 ships a {@code RedpandaContainerConnectionDetailsFactory}). No
 *       connection-details mechanism exists for the Schema Registry, so {@code schema.registry.url}
 *       is wired through {@code @DynamicPropertySource}, not the test property source block: an
 *       annotation attribute must be a compile-time constant and the registry's mapped port is
 *       known only after the container starts.
 * </ul>
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "spring.kafka.producer.properties.max.block.ms=30000",
            "spring.kafka.producer.properties.request.timeout.ms=30000",
            "spring.kafka.producer.properties.delivery.timeout.ms=30000",
            // Raises the registry REST client's retry/timeout bounds back up, as the producer
            // bounds above are.
            //
            // application-test.properties fail-fasts them (max.retries=0, 50ms timeouts), correct
            // for the 17 fixture-heavy classes whose registry has nothing listening; here the
            // registry is real, so those bounds would turn a slow lookup into a flaky failure.
            "spring.kafka.producer.properties.max.retries=3",
            "spring.kafka.producer.properties.retries.wait.ms=1000",
            "spring.kafka.producer.properties.http.connect.timeout.ms=30000",
            "spring.kafka.producer.properties.http.read.timeout.ms=30000"
        })
public abstract class AbstractKafkaContainerTest extends AbstractPostgresContainerTest {
    // The docker-java api.version=1.44 pin (testcontainers-java#11212) lives in
    // AbstractPostgresContainerTest's static initializer; JVM class initialization runs superclass
    // statics first, so it is in place before either container type starts.

    @ServiceConnection
    static final RedpandaContainer kafka =
            new RedpandaContainer(
                    DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v26.2.1"));

    // Imperative, exactly-once start (see the class Javadoc). Schemas register here too, through
    // the same AvroSchemaRegistrar.registerAll() the registerSchemas Gradle task invokes, so
    // auto.register.schemas=false needs no manual step (docs/CODE_STYLE.md rule 8).
    static {
        kafka.start();
        AvroSchemaRegistrar.registerAll(kafka.getSchemaRegistryAddress());
    }

    /**
     * Test-scoped hook that lets {@code SchemaRegistryOutageE2ETest} make the producer, and only
     * the producer, see an unreachable registry.
     *
     * <p>Why this is the way it is: a subclass-local {@code @DynamicPropertySource} overriding the
     * same key does not work. Spring invokes all such methods into one shared property source and
     * (confirmed empirically) invokes subclass-local methods before superclass ones, the opposite
     * of {@code @BeforeAll}, so the superclass method runs last and overwrites the subclass value.
     * This mutable field is the override point instead: it defaults to {@code null} (the real
     * container address), and the one test that needs it sets it in a {@code static} initializer
     * and resets it in an {@code @AfterAll}. Test classes in this package run sequentially in one
     * JVM (no parallel execution is configured), so no two contexts are built concurrently against
     * a transiently wrong value.
     */
    protected static volatile String producerSchemaRegistryUrlOverride;

    @DynamicPropertySource
    static void registerSchemaRegistryProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.kafka.producer.properties.schema.registry.url",
                () ->
                        producerSchemaRegistryUrlOverride != null
                                ? producerSchemaRegistryUrlOverride
                                : kafka.getSchemaRegistryAddress());
        registry.add(
                "spring.kafka.consumer.properties.schema.registry.url",
                kafka::getSchemaRegistryAddress);
    }

    @Autowired protected KafkaTemplate<String, Object> kafkaTemplate;
    @Autowired protected ActivityEventAvroMapper activityEventAvroMapper;

    protected String getBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    protected String getSchemaRegistryAddress() {
        return kafka.getSchemaRegistryAddress();
    }

    /**
     * Publishes {@code event} to {@link KafkaTopics#ACTIVITY}, keyed by its {@code eventId}, and
     * blocks until the broker acknowledges, timing out at 30 seconds.
     *
     * <p>Awaiting the ack makes a broker-side send rejection surface as this method's own
     * exception, not as a misleading 30-second Awaitility timeout that blames the consumer. The
     * event is mapped through {@link ActivityEventAvroMapper} first, because the wire format is
     * Avro, so callers keep handing it domain events.
     */
    protected void sendAndAwaitAck(ActivityEvent event)
            throws InterruptedException, ExecutionException, TimeoutException {
        kafkaTemplate
                .send(
                        KafkaTopics.ACTIVITY,
                        event.eventId().toString(),
                        activityEventAvroMapper.toAvro(event))
                .get(30, TimeUnit.SECONDS);
    }
}

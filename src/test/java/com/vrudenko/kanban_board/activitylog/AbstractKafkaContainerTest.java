package com.vrudenko.kanban_board.activitylog;

import com.vrudenko.kanban_board.config.AvroSchemaRegistrar;
import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.event.ActivityEvent;
import com.vrudenko.kanban_board.event.avro.ActivityEventAvroMapper;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * Shared real-broker-and-registry harness for the {@code activitylog} package's integration tests.
 * Starts one {@code docker.redpanda.com/redpandadata/redpanda:v26.2.1} container per test class --
 * one container exposing both a Kafka-protocol-compatible broker and a Confluent-API-compatible
 * Schema Registry (Phase 4, Schema Registry), replacing the previous {@code
 * apache/kafka-native:4.3.1} broker-only image. Migrating this shared base class in place (rather
 * than adding a second, Avro-specific harness) was the deliberate choice: the three pre-existing
 * {@code activitylog} E2E classes never touch a registry at all, so they compile and pass unchanged
 * against Redpanda purely because it is a Kafka-protocol superset of what they already exercised --
 * see this plan's design_alternatives for the full comparison against standing up a second,
 * standalone Confluent registry container.
 *
 * <p>Raises the test profile's producer bounds ({@code max.block.ms}, {@code request.timeout.ms},
 * {@code delivery.timeout.ms}) to 30 seconds for this context only. The test profile ({@code
 * application-test.properties}) deliberately bounds them at 50ms so a *missing* broker cannot slow
 * the full suite -- but against a real broker, 50ms is not enough for the first metadata fetch, and
 * the default 50ms bound would abort the first send before the container finishes announcing
 * itself. 30 seconds (not the original 10) matches the {@code Awaitility} ceiling every consuming
 * assertion in this package already uses: once Plan 02 added two more Testcontainers-backed test
 * classes sharing this one broker instance and one {@code activity-log} consumer group, the
 * cumulative produce/consume volume across all three classes in a single full-suite run can leave
 * the broker busy enough that a 10-second producer bound occasionally expires ({@code
 * org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s)...}) even though every
 * class passes cleanly in isolation. This is headroom for real broker load, not a hidden retry loop
 * or a softened assertion -- the bound only protects against an unreachable broker (its original
 * purpose); it does not affect what any test asserts.
 *
 * <p>Deliberately does not extend the shared application test base: the consumer path needs no
 * user, board, column or task fixture -- an activity row's board/user identifiers are plain
 * columns, and the consumer resolves no entity. The application test base's setup creates roughly
 * twenty entities through the real services, each of which now publishes an event into the very
 * broker under test, turning every test method into a race against unrelated traffic. This keeps
 * the no-mocking rule (docs/CODE_STYLE.md rule 4) fully honoured while avoiding that noise: real
 * Spring wiring, real broker, no fixtures this package does not need.
 *
 * <p>The container is started imperatively in a static initializer -- {@code kafka.start()} below
 * -- rather than via the {@code @Testcontainers}/{@code @Container} JUnit 5 extension. On this
 * environment (Windows + Docker Desktop, testcontainers-java 1.21.0), the extension's "singleton
 * container" pattern for a static {@code @Container} field did not reliably hold across this
 * package's three sibling test classes: instead of reusing the one already-running container, a
 * second, distinct container (a different Docker container ID, a different mapped port) was
 * observed starting when a second class in the package began running, while Spring's cached {@code
 * ApplicationContext} -- and the {@code KafkaTemplate}/{@code @KafkaListener} beans it had already
 * built against the *first* container's port -- was correctly reused unchanged. The result was
 * silent: those already-built beans kept talking to a stale port from a container that either no
 * longer existed or was no longer the one new test-local clients connected to, while a freshly
 * constructed raw client (via {@link #getBootstrapServers()}, evaluated fresh on every call) always
 * pointed at whatever container was current -- so Spring-mediated sends hung until they timed out
 * while raw test clients worked, exactly the split symptom that surfaced this. A plain, imperative
 * {@code kafka.start()} in a static initializer is guaranteed by JVM class-initialization semantics
 * to run exactly once per classloader, independent of any JUnit extension's lifecycle bookkeeping,
 * which is the standard Testcontainers "singleton container" recommendation for containers meant to
 * be shared across multiple test classes in one JVM.
 *
 * <p>{@code @ServiceConnection} on {@code kafka} still wires {@code spring.kafka.bootstrap-servers}
 * automatically -- Spring Boot 3.5.0 ships a dedicated {@code
 * RedpandaContainerConnectionDetailsFactory} for exactly this container type. No equivalent
 * connection-details mechanism exists for the Schema Registry (it is a Confluent/Avro concern, not
 * something Spring Boot's Kafka autoconfiguration knows about), so {@code schema.registry.url} is
 * wired explicitly below via {@code @DynamicPropertySource} instead of folded into the
 * {@code @TestPropertySource} block above it -- an annotation attribute must be a compile-time
 * constant, and the registry's mapped port is only known once the container has actually started.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            "spring.kafka.producer.properties.max.block.ms=30000",
            "spring.kafka.producer.properties.request.timeout.ms=30000",
            "spring.kafka.producer.properties.delivery.timeout.ms=30000"
        })
public abstract class AbstractKafkaContainerTest {
    /*
     * docker-java (bundled by Testcontainers 1.21.0) negotiates a Docker Engine API version that
     * Docker Engine 29.x rejects with a malformed {@code 400 Bad Request} on every transport (named
     * pipe and TCP alike) — {@code docker-java} issue matching testcontainers-java#11212. Pinning
     * the client to API 1.44 (Docker's own confirmed-working floor for this Engine generation)
     * resolves it without any host-level Docker Desktop configuration, so {@code ./gradlew test}
     * works out of the box on a fresh machine. Fixed upstream as the new default in
     * testcontainers-java 2.x; this pin can be dropped once this project upgrades past 1.21.0.
     */
    static {
        System.setProperty("api.version", "1.44");
    }

    @ServiceConnection
    static final RedpandaContainer kafka =
            new RedpandaContainer(
                    DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v26.2.1"));

    // Imperative, exactly-once start -- see the class Javadoc for why this replaces the
    // @Testcontainers/@Container-driven lifecycle. Schema registration happens here too, right
    // after the container is up: this is the same AvroSchemaRegistrar.registerAll() the
    // registerSchemas Gradle task invokes for build/CI, so this is what makes
    // auto.register.schemas=false workable in tests with zero manual setup step
    // (docs/CODE_STYLE.md rule 8), not a second, drifting registration path.
    static {
        kafka.start();
        AvroSchemaRegistrar.registerAll(kafka.getSchemaRegistryAddress());
    }

    @DynamicPropertySource
    static void registerSchemaRegistryProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.kafka.producer.properties.schema.registry.url",
                kafka::getSchemaRegistryAddress);
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
     * Publishes {@code event} to {@link KafkaTopics#ACTIVITY}, keyed by its own {@code eventId},
     * and blocks until the broker acknowledges the send, timing out at 30 seconds -- the same bound
     * this class's {@code @TestPropertySource} already applies to the producer's {@code
     * max.block.ms}/{@code request.timeout.ms}/{@code delivery.timeout.ms}. Every call site pairs
     * this with an Awaitility poll for the consumer's persisted effect; awaiting the ack here means
     * a broker-side send rejection surfaces immediately as this method's own exception instead of
     * as a misleading 30-second Awaitility timeout that would blame the consumer for a problem that
     * was actually the producer's.
     *
     * <p>Maps {@code event} through {@link ActivityEventAvroMapper} before sending -- the wire
     * format is Avro (Phase 4), not the plain domain record -- so this is the one internal change
     * that keeps all three pre-existing {@code activitylog} E2E classes compiling and passing with
     * zero edits to their own source: they hand this helper domain events, and it is this helper's
     * job, not theirs, to know the wire format.
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

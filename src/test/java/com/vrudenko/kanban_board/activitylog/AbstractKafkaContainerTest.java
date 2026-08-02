package com.vrudenko.kanban_board.activitylog;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared real-broker harness for the {@code activitylog} package's integration tests. Starts one
 * {@code apache/kafka-native:4.3.1} container per test class -- the same image tag {@code
 * docker-compose.yml} pins, so local, CI and test all exercise the same broker family.
 *
 * <p>Raises the test profile's producer bounds ({@code max.block.ms}, {@code request.timeout.ms},
 * {@code delivery.timeout.ms}) to 10 seconds for this context only. The test profile ({@code
 * application-test.properties}) deliberately bounds them at 50ms so a *missing* broker cannot slow
 * the full suite -- but against a real broker, 50ms is not enough for the first metadata fetch, and
 * the default 50ms bound would abort the first send before the container finishes announcing
 * itself.
 *
 * <p>Deliberately does not extend the shared application test base: the consumer path needs no
 * user, board, column or task fixture -- an activity row's board/user identifiers are plain
 * columns, and the consumer resolves no entity. The application test base's setup creates roughly
 * twenty entities through the real services, each of which now publishes an event into the very
 * broker under test, turning every test method into a race against unrelated traffic. This keeps
 * the no-mocking rule (docs/CODE_STYLE.md rule 4) fully honoured while avoiding that noise: real
 * Spring wiring, real broker, no fixtures this package does not need.
 */
@Testcontainers
@SpringBootTest
@TestPropertySource(
        properties = {
            "spring.kafka.producer.properties.max.block.ms=10000",
            "spring.kafka.producer.properties.request.timeout.ms=10000",
            "spring.kafka.producer.properties.delivery.timeout.ms=10000"
        })
public abstract class AbstractKafkaContainerTest {
    /**
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

    @Container @ServiceConnection
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.3.1"));

    protected String getBootstrapServers() {
        return kafka.getBootstrapServers();
    }
}

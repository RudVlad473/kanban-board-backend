package com.vrudenko.kanban_board.support.containers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one real PostgreSQL container for the whole JVM run, shared by every Spring test context in
 * the repository.
 *
 * <p>Why this is the way it is:
 *
 * <ul>
 *   <li>A third shared ancestor: {@link com.vrudenko.kanban_board.support.fixtures.AbstractAppTest}
 *       and {@link com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest} extend
 *       it, as do {@code KanbanBoardApplicationTests} and {@code ActivityEventAvroMapperTest}.
 *       {@code AbstractKafkaContainerTest} does not extend {@code AbstractAppTest} and never will,
 *       yet its 9 subclasses persist {@code ActivityLogEntity} and need a real datasource; a
 *       container reachable only from {@code AbstractAppTest} would strand them or force a second
 *       container. {@link com.vrudenko.kanban_board.config.FlywaySchemaProvenanceTest} is the
 *       standing proof of container/Flyway/Hibernate/Spring-Session coexistence: it asserts
 *       Flyway-only artifacts Hibernate's naming strategy would never emit, so a silent regression
 *       to Hibernate-generated DDL fails the build.
 *   <li>The container starts imperatively in a static initializer, as in {@code
 *       AbstractKafkaContainerTest}, never via the {@code @Testcontainers}/{@code @Container}
 *       extension, whose singleton-container pattern proved unreliable across sibling classes for
 *       the Kafka container: a second container started for a later class while Spring's cached
 *       context kept the first's stale port (see that class's Javadoc). A {@code static {
 *       postgres.start(); }} block runs exactly once per classloader, regardless of JUnit
 *       lifecycle.
 *   <li>The {@code api.version} pin lives here, not in {@code AbstractKafkaContainerTest}, because
 *       JVM class initialization runs a superclass's static initializers first, so the pin fires
 *       before either container type starts, whichever concrete class loads first.
 *   <li>{@code @ServiceConnection} on {@code postgres} supplies {@code spring.datasource.url},
 *       {@code username} and {@code password} (Spring Boot ships a {@code
 *       PostgresContainerConnectionDetailsFactory}), so no {@code @DynamicPropertySource} is
 *       needed; {@code AbstractKafkaContainerTest} needs one only because no ConnectionDetails type
 *       exists for a schema registry. A subclass-local {@code @DynamicPropertySource} overriding a
 *       superclass-registered key would lose, because Spring invokes subclass methods first;
 *       harmless today because this class registers none.
 * </ul>
 */
public abstract class AbstractPostgresContainerTest {
    /*
     * Pins docker-java's Docker Engine API version to 1.44 (testcontainers-java#11212).
     *
     * Testcontainers 1.21.0's docker-java negotiates a version Docker Engine 29.x rejects with a
     * malformed 400 on every transport; 1.44 is Docker's confirmed-working floor for this Engine
     * generation, so `./gradlew test` works on a fresh machine (docs/CODE_STYLE.md rule 8). The
     * pin sits here so it covers both container types.
     */
    static {
        System.setProperty("api.version", "1.44");
    }

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    // Imperative, exactly-once start (see the class Javadoc). postgres:16 matches
    // docker-compose.yml's image tag, so local dev and tests do not diverge on major version.
    static {
        postgres.start();
    }
}

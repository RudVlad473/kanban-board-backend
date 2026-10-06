package com.vrudenko.kanban_board.support.containers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The one real PostgreSQL container for the whole JVM run, shared by every Spring test context in
 * the repository.
 *
 * Why this is the way it is:
 *
 * - A third shared ancestor: com.vrudenko.kanban_board.support.fixtures.AbstractAppTest
 *   and com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest extend
 *   it, as do KanbanBoardApplicationTests and ActivityEventAvroMapperTest.
 *   AbstractKafkaContainerTest does not extend AbstractAppTest and never will,
 *   yet its 9 subclasses persist ActivityLogEntity and need a real datasource; a
 *   container reachable only from AbstractAppTest would strand them or force a second
 *   container. com.vrudenko.kanban_board.config.FlywaySchemaProvenanceTest is the
 *   standing proof of container/Flyway/Hibernate/Spring-Session coexistence: it asserts
 *   Flyway-only artifacts Hibernate's naming strategy would never emit, so a silent regression
 *   to Hibernate-generated DDL fails the build.
 * - The container starts imperatively in a static initializer, as in
 *   AbstractKafkaContainerTest, never via the @Testcontainers/@Container
 *   extension, whose singleton-container pattern proved unreliable across sibling classes for
 *   the Kafka container: a second container started for a later class while Spring's cached
 *   context kept the first's stale port (see that class's Javadoc). A static {
 *   postgres.start(); } block runs exactly once per classloader, regardless of JUnit
 *   lifecycle.
 * - The api.version pin lives here, not in AbstractKafkaContainerTest, because
 *   JVM class initialization runs a superclass's static initializers first, so the pin fires
 *   before either container type starts, whichever concrete class loads first.
 * - @ServiceConnection on postgres supplies spring.datasource.url,
 *   username and password (Spring Boot ships a
 *   PostgresContainerConnectionDetailsFactory), so no @DynamicPropertySource is
 *   needed; AbstractKafkaContainerTest needs one only because no ConnectionDetails type
 *   exists for a schema registry. A subclass-local @DynamicPropertySource overriding a
 *   superclass-registered key would lose, because Spring invokes subclass methods first;
 *   harmless today because this class registers none.
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

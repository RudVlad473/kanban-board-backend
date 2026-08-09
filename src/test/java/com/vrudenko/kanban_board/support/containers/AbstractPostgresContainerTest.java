package com.vrudenko.kanban_board.support.containers;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared, third ancestor providing exactly one real PostgreSQL container for the whole JVM run
 * (04.2, D-01). Both {@link com.vrudenko.kanban_board.support.fixtures.AbstractAppTest} and {@link
 * com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest} extend this class, as do
 * the two {@code @SpringBootTest} classes that extend neither hierarchy ({@code
 * KanbanBoardApplicationTests} and {@code ActivityEventAvroMapperTest}) -- so every Spring test
 * context in the repository boots against this one container. {@link
 * com.vrudenko.kanban_board.FlywaySchemaProvenanceTest} is the standing proof of
 * container/Flyway/Hibernate/Spring-Session coexistence: it asserts the schema carries Flyway-only
 * artifacts Hibernate's naming strategy would never emit, so a silent regression to
 * Hibernate-generated DDL fails the build rather than passing quietly.
 *
 * <p>This is a <em>third</em> shared ancestor rather than folding the container into {@code
 * AbstractAppTest} because {@code AbstractKafkaContainerTest} does not extend {@code
 * AbstractAppTest} and deliberately never will (see its own Javadoc), yet its 9 subclasses persist
 * {@code ActivityLogEntity} and therefore need a real datasource too. A container reachable only
 * from {@code AbstractAppTest} would either strand those 9 classes without a datasource or force a
 * second, redundant container -- D-01 rules both out by naming this base class as the common
 * ancestor both hierarchies inherit.
 *
 * <p>The container is started imperatively in a plain {@code static} initializer, exactly like
 * {@code AbstractKafkaContainerTest}'s {@code RedpandaContainer} -- never via the
 * {@code @Testcontainers}/{@code @Container} JUnit 5 extension. That extension-driven "singleton
 * container" pattern was already found unreliable across sibling test classes in this environment
 * for the Kafka container (see {@code AbstractKafkaContainerTest}'s Javadoc and STATE.md's Phase 3
 * Plan 02 entry): a second, distinct container silently started for a later class while Spring's
 * cached {@code ApplicationContext} kept talking to the first, stale one. A plain {@code static {
 * postgres.start(); }} block is guaranteed by JVM class-initialization semantics to run exactly
 * once per classloader, independent of any JUnit extension lifecycle -- there is no reason to
 * expect Postgres to behave differently from Kafka here, so the same fix applies unconditionally.
 *
 * <p>The {@code api.version} system property pin lives in <em>this</em> class rather than being
 * duplicated in (or left solely in) {@code AbstractKafkaContainerTest} because JVM
 * class-initialization runs a superclass's static initializers before a subclass's. Since {@code
 * AbstractKafkaContainerTest} extends this class, putting the pin here guarantees it fires before
 * EITHER container type starts, regardless of which concrete test class the JVM happens to load
 * first -- a guarantee two independent, class-local pins could not offer if load order ever varied.
 *
 * <p>{@code @ServiceConnection} on {@code postgres} is what supplies {@code
 * spring.datasource.url}/{@code username}/{@code password} to any Spring context built from a
 * subclass -- Spring Boot 3.1+ ships a dedicated {@code PostgresContainerConnectionDetailsFactory}
 * for exactly this container type, so no {@code @DynamicPropertySource} is needed or wanted here.
 * This is a genuinely different situation from {@code AbstractKafkaContainerTest}, which still
 * needs a {@code @DynamicPropertySource} for {@code schema.registry.url} only because Spring Boot
 * ships no {@code ConnectionDetails} type for a schema registry -- Postgres has one, a schema
 * registry does not. Worth remembering: a subclass-local {@code @DynamicPropertySource} attempting
 * to override a superclass-registered property key would lose, since Spring discovers and invokes
 * subclass methods before superclass ones (STATE.md, Phase 4 Plan 03) -- not a concern for this
 * class today (it registers nothing via {@code @DynamicPropertySource}), but a landmine to avoid
 * reintroducing if that ever changes.
 */
public abstract class AbstractPostgresContainerTest {
    /*
     * docker-java (bundled by Testcontainers 1.21.0) negotiates a Docker Engine API version that
     * Docker Engine 29.x rejects with a malformed 400 Bad Request on every transport --
     * testcontainers-java#11212. Pinning the client to API 1.44 (Docker's own confirmed-working
     * floor for this Engine generation) resolves it with zero host-level Docker Desktop
     * configuration, so `./gradlew test` works out of the box on a fresh machine
     * (docs/CODE_STYLE.md rule 8). Moved up from AbstractKafkaContainerTest so this single pin
     * covers both container types, since that class now extends this one.
     */
    static {
        System.setProperty("api.version", "1.44");
    }

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    // Imperative, exactly-once start -- see the class Javadoc for why this replaces the
    // @Testcontainers/@Container-driven lifecycle. postgres:16 matches docker-compose.yml's own
    // image tag exactly, so local dev, tests, and the Phase 5 Neon target do not silently diverge
    // on major version.
    static {
        postgres.start();
    }
}

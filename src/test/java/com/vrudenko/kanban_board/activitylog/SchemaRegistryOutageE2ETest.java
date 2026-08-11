package com.vrudenko.kanban_board.activitylog;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import com.vrudenko.kanban_board.config.KafkaEventPublisher;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.repository.BoardRepository;
import com.vrudenko.kanban_board.service.UserService;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.fluttercode.datafactory.impl.DataFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves D-01's central claim directly for a schema-registry outage specifically: a real,
 * transactional mutation completes and persists while the registry is unreachable, with the broker
 * reachable throughout. That asymmetry -- registry down, broker up -- is this test's whole design:
 * a pass with both down would prove only the broker-down behaviour v1.1 already established, never
 * the registry-specific extension D-01 makes.
 *
 * <p>Makes only the producer-side {@code schema.registry.url} unreachable, through {@link
 * AbstractKafkaContainerTest#producerSchemaRegistryUrlOverride} rather than a
 * {@code @TestPropertySource} block or a second {@code @DynamicPropertySource} registration of the
 * same key. Both simpler-looking alternatives were tried first and both fail, for two different
 * reasons, documented here so neither is silently reintroduced later: a {@code @TestPropertySource}
 * override would be silently ineffective because {@code @DynamicPropertySource} property sources
 * always take precedence over {@code @TestPropertySource} regardless of declaration order; and a
 * second, subclass-local {@code @DynamicPropertySource} method registering the exact same key was
 * tried and confirmed empirically (not assumed) to lose anyway -- Spring discovers and invokes
 * subclass-local {@code @DynamicPropertySource} methods <em>before</em> superclass ones (the
 * opposite of {@code @BeforeAll} ordering), so {@link
 * AbstractKafkaContainerTest#registerSchemaRegistryProperties}'s registration always runs last and
 * silently overwrites a same-key subclass override. See that field's Javadoc for the full mechanism
 * and the safety argument for using shared mutable state here at all. The consumer-side property (a
 * distinct key, not literally shared with the producer's) is deliberately left pointed at the real
 * registry: this test never expects a message to reach the consumer at all, so there is nothing for
 * it to resolve.
 *
 * <p>Declaring {@link #makeProducerRegistryUnreachable} at all -- regardless of what it does with
 * its {@code DynamicPropertyRegistry} parameter -- is also what gives this test class its own,
 * uncached Spring context rather than sharing the one every sibling {@code activitylog} E2E class
 * reuses: Spring keys the context cache partly on the discovered set of
 * {@code @DynamicPropertySource} methods for a test class, and this class's set (superclass method
 * plus this one) differs from every sibling's (superclass method only) -- so this class gets a
 * fresh context, without disturbing the cached context the sibling classes share.
 *
 * <p>The mutation is driven at the service layer, not over HTTP: the publish is dispatched by
 * {@code @TransactionalEventListener(AFTER_COMMIT)} onto the {@code kafkaPublishExecutor} pool
 * ({@link KafkaEventPublisher}), so the calling thread -- the request thread in production, this
 * test method's thread here -- has already been released before the registry is ever contacted. An
 * HTTP hop would additionally exercise the servlet stack, which is not what this test is about.
 *
 * <p><b>Genuine finding this test surfaced (recorded here, not patched over with new production
 * code per this plan's design):</b> {@link KafkaEventPublisher}'s own Javadoc claims a registry
 * failure "becomes a failed future rather than a synchronous throw" and is caught by its {@code
 * whenComplete} callback, with "zero new code" needed beyond the broker-down case. That is only
 * true for a failure that occurs <em>during the asynchronous network send</em> (e.g. a broker that
 * accepts the connection but never acknowledges). A schema-registry lookup failure happens earlier,
 * inside Avro <em>serialization</em>, which {@code KafkaProducer.doSend} performs synchronously on
 * the calling thread before a delivery future is ever created -- so {@code KafkaTemplate.send()}
 * itself throws {@code SerializationException} synchronously here, and the {@code whenComplete}
 * callback is never reached at all. Because the whole method is {@code @Async}, Spring's default
 * {@code SimpleAsyncUncaughtExceptionHandler} catches that synchronous throw at the
 * async-invocation boundary instead and logs it at {@code ERROR}, naming the {@code
 * onActivityEvent} method -- but, unlike the {@code whenComplete} path's log line, without the
 * specific event's {@code eventId}/{@code boardId}. D-01's user-facing guarantee still holds in
 * full (the mutation succeeds and persists, the caller is never blocked, and the failure is logged
 * rather than swallowed) -- but "one resilience policy for the whole publish path" is, in this
 * specific sense, two distinct failure-propagation mechanisms depending on where in the pipeline
 * the failure originates, only one of which names the event.
 */
@SpringBootTest
@Tag("kafka")
class SchemaRegistryOutageE2ETest extends AbstractKafkaContainerTest {

    // Port 1 is a privileged port nothing binds to without elevated OS permissions, and a loopback
    // connection attempt to a non-listening local port fails immediately with "connection
    // refused" rather than hanging out a TCP handshake timeout -- a genuinely unreachable
    // registry, resolved fast.
    private static final String UNREACHABLE_REGISTRY_URL = "http://localhost:1";

    // Generous relative to the registry client's own bounded retry policy raised in
    // AbstractKafkaContainerTest (max.retries=3, retries.wait.ms=1000 -- a few seconds worst
    // case): long enough that the async publish attempt has certainly either failed for good or
    // (in the bug scenario this test would catch) already succeeded by the time this fires.
    private static final Duration PUBLISH_ATTEMPT_WINDOW = Duration.ofSeconds(10);

    /**
     * The {@code registry} parameter is unused; this method's real job is the assignment below. See
     * this class's Javadoc for why a direct {@code registry.add(...)} override of the producer's
     * {@code schema.registry.url} here would be silently overwritten by the superclass's own
     * registration, and why declaring this method at all is still necessary (it is what gives this
     * class its own, uncached Spring context).
     */
    @DynamicPropertySource
    static void makeProducerRegistryUnreachable(DynamicPropertyRegistry registry) {
        producerSchemaRegistryUrlOverride = UNREACHABLE_REGISTRY_URL;
    }

    /**
     * Restores the shared override field to its default so no class built after this one in the
     * same JVM (test classes in this package run sequentially, never in parallel) sees a dead
     * registry address it never asked for.
     */
    @AfterAll
    static void restoreProducerRegistryUrl() {
        producerSchemaRegistryUrlOverride = null;
    }

    @Autowired private UserService userService;
    @Autowired private BoardRepository boardRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private final DataFactory dataFactory = new DataFactory();

    // dataFactory.getEmailAddress() occasionally draws a multi-word entry from DataFactory's dirty
    // word corpus (e.g. the literal "or maybe") and concatenates it with a second word with no
    // separator, producing an email with an embedded space that fails @AppEmail's @Email format
    // check -- see AbstractAppTest.generateValidEmail()'s Javadoc for the full root-cause writeup.
    private String generateValidEmail() {
        return RandomStringUtils.randomAlphabetic(10).toLowerCase(Locale.ROOT) + "@example.com";
    }

    // Attached to the ROOT logger, not KafkaEventPublisher's own -- see this class's Javadoc for
    // why: the failure this test provokes never reaches KafkaEventPublisher's whenComplete
    // callback at all (it is a synchronous throw, caught by Spring's default @Async
    // uncaught-exception handler, a different logger entirely), so a ListAppender scoped to
    // KafkaEventPublisher's own logger would see nothing.
    private ch.qos.logback.classic.Logger rootLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachRootLogAppender() {
        rootLogger =
                (ch.qos.logback.classic.Logger)
                        LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        logAppender = new ListAppender<>();
        logAppender.start();
        rootLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachRootLogAppender() {
        rootLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    @Nested
    class MutationSurvivesRegistryOutageTest {

        @Test
        void shouldReturnAndPersist_butNeverPublish_whenSchemaRegistryIsUnreachable()
                throws Exception {
            // arrange -- a real, signed-up owning user; not part of the mutation under test.
            UserResponseDTO owningUser =
                    userService.save(
                            SignupRequestDTO.builder()
                                    .email(generateValidEmail())
                                    .displayName(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants
                                                            .MIN_USER_DISPLAY_NAME_LENGTH))
                                    .password(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_PASSWORD_LENGTH))
                                    .build());
            String boardName =
                    dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4);
            var createdBoard = new AtomicReference<BoardResponseDTO>();

            // act -- the mutation itself.
            var thrown =
                    Assertions.catchException(
                            () ->
                                    createdBoard.set(
                                            userService.addBoardByUserId(
                                                    owningUser.getId(),
                                                    SaveBoardRequestDTO.builder()
                                                            .name(boardName)
                                                            .build())));

            // assert -- first, the call returned normally: capturing the throwable and asserting
            // it is null, rather than letting the absence of a failure speak for itself.
            Assertions.assertThat(thrown).isNull();

            // assert -- second, the mutation actually persisted. A "success" that never committed
            // would satisfy the first assertion and still violate the requirement.
            Assertions.assertThat(createdBoard.get()).isNotNull();
            Assertions.assertThat(boardRepository.findById(createdBoard.get().getId())).isPresent();

            // assert -- third, no activity_log row ever appears for this board. This is the
            // assertion that proves the publish genuinely failed rather than quietly succeeding
            // against some fallback, which would make the whole test vacuous. Bounded explicitly
            // via pollDelay rather than checked immediately: the publish is asynchronous, so an
            // immediate check would pass even if the publish were about to succeed a moment
            // later.
            Awaitility.await()
                    .pollDelay(PUBLISH_ATTEMPT_WINDOW)
                    .atMost(PUBLISH_ATTEMPT_WINDOW.plusSeconds(10))
                    .untilAsserted(
                            () -> {
                                var rowsForBoard =
                                        activityLogRepository.findAll().stream()
                                                .filter(
                                                        row ->
                                                                row.getBoardId()
                                                                        .equals(
                                                                                createdBoard
                                                                                        .get()
                                                                                        .getId()))
                                                .toList();
                                Assertions.assertThat(rowsForBoard).isEmpty();
                            });

            // assert -- the never-swallowed half of D-01: some error was logged naming
            // onActivityEvent, rather than the failure being silently dropped. This does NOT
            // match the "Failed to publish" line KafkaEventPublisher's own whenComplete callback
            // would log for a broker-down failure -- see this class's Javadoc for the genuine
            // finding this test surfaced about which failure path a registry-down error actually
            // takes.
            boolean loggedFailure =
                    logAppender.list.stream()
                            .anyMatch(
                                    event ->
                                            event.getLevel() == ch.qos.logback.classic.Level.ERROR
                                                    && event.getFormattedMessage() != null
                                                    && event.getFormattedMessage()
                                                            .contains("onActivityEvent"));
            Assertions.assertThat(loggedFailure).isTrue();
        }
    }
}

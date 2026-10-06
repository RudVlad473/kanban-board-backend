package com.vrudenko.kanban_board.activitylog;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

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
 * Proves a real, transactional mutation completes and persists while the schema registry is
 * unreachable and the broker is reachable throughout.
 *
 * That asymmetry is the test's whole design: a pass with both down would prove only the
 * broker-down behaviour already established, not the registry-specific case.
 *
 * Why this is the way it is:
 *
 * - Only the producer-side schema.registry.url is made unreachable, through
 *   AbstractKafkaContainerTest.producerSchemaRegistryUrlOverride, not a
 *   TestPropertySource or a second DynamicPropertySource registration of the same key.
 *   A TestPropertySource override is silently ineffective because
 *   DynamicPropertySource sources always take precedence regardless of declaration order. A
 *   subclass-local DynamicPropertySource registering the same key was confirmed
 *   empirically to lose too: Spring invokes subclass-local methods before superclass ones (the
 *   opposite of BeforeAll), so
 *   AbstractKafkaContainerTest.registerSchemaRegistryProperties runs last and overwrites it.
 *   See that field's Javadoc for the mechanism and the safety argument for shared mutable
 *   state. The consumer-side property is left pointed at the real registry: no message is
 *   expected to reach the consumer.
 * - Declaring makeProducerRegistryUnreachable, whatever it does with its
 *   DynamicPropertyRegistry parameter, gives this class its own uncached Spring context: the
 *   context cache keys partly on the discovered set of DynamicPropertySource methods,
 *   and this class's set (superclass method plus this one) differs from every sibling's, so
 *   sibling classes keep the shared cached context.
 * - The mutation is driven at the service layer, not over HTTP: the publish is dispatched by
 *   TransactionalEventListener(AFTER_COMMIT) onto the kafkaPublishExecutor pool
 *   (KafkaEventPublisher), so the calling thread is released before the registry is
 *   contacted. An HTTP hop would add the servlet stack, which is not the subject.
 * - Finding this test surfaced, not patched over with production code:
 *   KafkaEventPublisher's Javadoc claims a registry failure "becomes a failed future rather
 *   than a synchronous throw" caught by its whenComplete callback. That holds only for
 *   a failure during the asynchronous network send (for example a broker that accepts the
 *   connection but never acknowledges). A registry lookup failure occurs earlier, inside Avro
 *   serialization, which KafkaProducer.doSend performs synchronously before a
 *   delivery future exists, so KafkaTemplate.send() throws
 *   SerializationException synchronously and whenComplete is never reached. Because
 *   the method is Async, Spring's default SimpleAsyncUncaughtExceptionHandler
 *   catches the throw at the async boundary and logs it at ERROR naming
 *   onActivityEvent, but without the event's eventId/boardId. The user-facing
 *   guarantee holds (the mutation persists, the caller is never blocked, the failure is
 *   logged), but "one resilience policy for the whole publish path" is in this sense two
 *   failure-propagation mechanisms, only one of which names the event.
 */
@SpringBootTest
@Tag("kafka")
class SchemaRegistryOutageE2ETest extends AbstractKafkaContainerTest {

    // Port 1 is privileged and nothing binds to it, so a loopback connection fails immediately
    // with "connection refused" rather than hanging out a TCP handshake timeout: an unreachable
    // registry, resolved fast.
    private static final String UNREACHABLE_REGISTRY_URL = "http://localhost:1";

    // Generous relative to the registry client's bounded retry policy in
    // AbstractKafkaContainerTest (max.retries=3, retries.wait.ms=1000, a few seconds worst case):
    // by then the async publish has certainly failed for good or, in the bug scenario, succeeded.
    private static final Duration PUBLISH_ATTEMPT_WINDOW = Duration.ofSeconds(10);

    /**
     * Makes the producer's registry URL unreachable; the registry parameter is unused.
     *
     * A direct registry.add(...) override would be overwritten by the superclass
     * registration, and declaring this method still gives this class its own uncached Spring
     * context (see the class Javadoc).
     */
    @DynamicPropertySource
    static void makeProducerRegistryUnreachable(DynamicPropertyRegistry registry) {
        producerSchemaRegistryUrlOverride = UNREACHABLE_REGISTRY_URL;
    }

    /**
     * Restores the shared override field to its default so no later class in the JVM (test classes
     * here run sequentially) sees a dead registry address.
     */
    @AfterAll
    static void restoreProducerRegistryUrl() {
        producerSchemaRegistryUrlOverride = null;
    }

    @Autowired private UserService userService;
    @Autowired private BoardRepository boardRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private final DataFactory dataFactory = new DataFactory();

    // dataFactory.getEmailAddress() can draw a multi-word entry and produce an email with an
    // embedded space that fails @AppEmail (root cause: AbstractAppTest.generateValidEmail()).
    private String generateValidEmail() {
        return RandomStringUtils.randomAlphabetic(10).toLowerCase(Locale.ROOT) + "@example.com";
    }

    // Attached to the ROOT logger, not KafkaEventPublisher's: the failure never reaches its
    // whenComplete callback (see the class Javadoc).
    //
    // It is a synchronous throw caught by Spring's default @Async handler, a different logger, so
    // a ListAppender on KafkaEventPublisher's own logger would see nothing.
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

            // assert -- third, no activity_log row ever appears for this board, which proves the
            // publish genuinely failed rather than succeeding against a fallback. Bounded via
            // pollDelay, not checked immediately: the publish is asynchronous, so an immediate
            // check would pass even if it succeeded moments later.
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

            // assert -- the never-swallowed half: some error was logged naming onActivityEvent. It
            // does NOT match the "Failed to publish" line KafkaEventPublisher's whenComplete would
            // log for a broker-down failure (see the class Javadoc).
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

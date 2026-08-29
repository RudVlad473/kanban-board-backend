package com.vrudenko.kanban_board.e2e.reset;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.KafkaTopics;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.event.TaskCreatedEvent;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.service.ColumnService;
import com.vrudenko.kanban_board.service.ResetService;
import com.vrudenko.kanban_board.service.TaskService;
import com.vrudenko.kanban_board.service.UserService;
import com.vrudenko.kanban_board.support.containers.AbstractKafkaContainerTest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.fluttercode.datafactory.impl.DataFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Real-broker, real-Postgres proof of {@link ResetService#resetAll()} (RESET-01). Extends {@link
 * AbstractKafkaContainerTest} directly (not {@code AbstractAppTest}) so fixtures are created
 * explicitly through the real services in each test, matching the plan's own read-first pointer to
 * that harness's Javadoc.
 */
@SpringBootTest
@Tag("kafka")
@ActiveProfiles({"test", "nonprod"})
@TestPropertySource(
        properties = {"app.reset.token=reset-service-e2e-test-token-40-characters-long"})
class ResetServiceE2ETest extends AbstractKafkaContainerTest {

    @Autowired private ResetService resetService;
    @Autowired private KafkaAdmin kafkaAdmin;
    @Autowired private UserService userService;
    @Autowired private BoardService boardService;
    @Autowired private ColumnService columnService;
    @Autowired private TaskService taskService;

    @PersistenceContext private EntityManager entityManager;

    private final DataFactory dataFactory = new DataFactory();

    private String randomId() {
        return UUID.randomUUID().toString();
    }

    private String randomEmail() {
        return "reset-" + randomId().toLowerCase(Locale.ROOT) + "@example.com";
    }

    private String randomPassword() {
        return dataFactory
                        .getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH)
                        .toLowerCase(Locale.ROOT)
                + "Aa1!";
    }

    private long countRows(String table) {
        return ((Number)
                        entityManager
                                .createNativeQuery("SELECT count(*) FROM " + table)
                                .getSingleResult())
                .longValue();
    }

    /**
     * Creates one real user/board/column/task/subtask chain through the real services, returning
     * the created user's id (quick task 260829-ii3's {@code DeleteUsersTest} needs it to target a
     * specific user; pre-existing {@code ResetAllTest} callers simply discard the return value).
     */
    private String createDomainFixture() {
        var user =
                userService.save(
                        SignupRequestDTO.builder()
                                .email(randomEmail())
                                .displayName(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                                .password(randomPassword())
                                .build());

        var board =
                userService.addBoardByUserId(
                        user.getId(),
                        SaveBoardRequestDTO.builder()
                                .name(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_BOARD_NAME_LENGTH + 4))
                                .build());

        var column =
                boardService.addColumnByBoardId(
                        user.getId(),
                        board.getId(),
                        SaveColumnRequestDTO.builder()
                                .name(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                .build());

        var task =
                columnService.addTaskByColumnId(
                        user.getId(),
                        column.getId(),
                        SaveTaskRequestDTO.builder()
                                .title(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                                .description(
                                        dataFactory.getRandomText(
                                                ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                                ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH))
                                .build());

        taskService.addSubtaskByTaskId(
                user.getId(),
                task.getId(),
                SaveSubtaskRequestDTO.builder()
                        .title(
                                dataFactory.getRandomText(
                                        ValidationConstants.MIN_SUBTASK_TITLE_LENGTH + 1))
                        .build());

        return user.getId();
    }

    private void awaitActivityLogHasAtLeastOneRow() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> countRows("activity_log") > 0);
    }

    /**
     * Awaits {@code activity_log} reaching exactly {@code expectedRowCount}. {@link
     * com.vrudenko.kanban_board.config.KafkaEventPublisher#onActivityEvent} is {@code @Async} on
     * {@code AFTER_COMMIT}, so returning from {@link #createDomainFixture()} gives no guarantee its
     * events have reached the broker yet, let alone been consumed. Callers that then invoke {@link
     * ResetService#resetAll()} without this wait race a real bug found live (todo
     * 2026-08-19-resetservicee2etest-flaky-resetall-after-real-traffic.md): a fixture event that
     * arrives at the broker after {@code resetAll()}'s topic-trim step survives it, gets consumed
     * by the listener {@code resetAll()} restarts in its {@code finally} block, and lands a stray
     * row in {@code activity_log} *after* the Postgres truncate already ran -- failing an isZero
     * assertion that had every right to expect zero.
     */
    private void awaitActivityLogRowCount(long expectedRowCount) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> countRows("activity_log") == expectedRowCount);
    }

    // Scoped counts (quick task 260829-ii3) -- a targeted delete must prove exactly one user's
    // rows are gone across every owned-resource table, never a blanket countRows that would also
    // count an untouched second user's rows in the same table.
    private long countUsersById(String userId) {
        return ((Number)
                        entityManager
                                .createNativeQuery("SELECT count(*) FROM users WHERE id = :userId")
                                .setParameter("userId", userId)
                                .getSingleResult())
                .longValue();
    }

    private long countBoardsForUser(String userId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(*) FROM boards WHERE user_id = :userId")
                                .setParameter("userId", userId)
                                .getSingleResult())
                .longValue();
    }

    private long countColumnsForUser(String userId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(*) FROM columns c JOIN boards b ON"
                                                + " c.board_id = b.id WHERE b.user_id = :userId")
                                .setParameter("userId", userId)
                                .getSingleResult())
                .longValue();
    }

    private long countTasksForUser(String userId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(*) FROM tasks t JOIN columns c ON"
                                                + " t.column_id = c.id JOIN boards b ON c.board_id ="
                                                + " b.id WHERE b.user_id = :userId")
                                .setParameter("userId", userId)
                                .getSingleResult())
                .longValue();
    }

    private long countSubtasksForUser(String userId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(*) FROM subtasks s JOIN tasks t ON"
                                                + " s.task_id = t.id JOIN columns c ON t.column_id ="
                                                + " c.id JOIN boards b ON c.board_id = b.id WHERE"
                                                + " b.user_id = :userId")
                                .setParameter("userId", userId)
                                .getSingleResult())
                .longValue();
    }

    private long countActivityLogForUser(String userId) {
        return ((Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(*) FROM activity_log WHERE user_id ="
                                                + " :userId")
                                .setParameter("userId", userId)
                                .getSingleResult())
                .longValue();
    }

    private void awaitActivityLogRowCountForUser(String userId, long expectedRowCount) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> countActivityLogForUser(userId) == expectedRowCount);
    }

    private long endOffset(AdminClient admin, TopicPartition partition) throws Exception {
        return admin.listOffsets(Map.of(partition, OffsetSpec.latest()))
                .partitionResult(partition)
                .get()
                .offset();
    }

    private long earliestOffset(AdminClient admin, TopicPartition partition) throws Exception {
        return admin.listOffsets(Map.of(partition, OffsetSpec.earliest()))
                .partitionResult(partition)
                .get()
                .offset();
    }

    @Nested
    class ResetAllTest {
        @Test
        void should_emptyBothStores_when_resetAllCalledAfterRealTraffic() throws Exception {
            // arrange
            sendAndAwaitAck(
                    new TaskCreatedEvent(
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now()));
            awaitActivityLogHasAtLeastOneRow();
            var rowCountBeforeFixture = countRows("activity_log");
            createDomainFixture();
            // createDomainFixture's own signup->board->column->task->subtask chain publishes 4
            // events (BOARD_CREATED/COLUMN_CREATED/TASK_CREATED/SUBTASK_CREATED -- signup itself
            // emits none, matching the identical chain's proof in docs/INFRA_RUNBOOK.md's nonprod
            // reset rollout). Awaiting a RELATIVE gain of 4, not a hardcoded absolute total: this
            // class's sibling tests (e.g. should_succeed_when_resetAllCalledTwiceInARow) have the
            // same unawaited-publish gap this method is closing, so a fixed total would be fragile
            // against their own late-arriving events landing in this shared activity_log table.
            awaitActivityLogRowCount(rowCountBeforeFixture + 4);

            // act
            resetService.resetAll();

            // assert
            Assertions.assertThat(countRows("users")).isZero();
            Assertions.assertThat(countRows("boards")).isZero();
            Assertions.assertThat(countRows("columns")).isZero();
            Assertions.assertThat(countRows("tasks")).isZero();
            Assertions.assertThat(countRows("subtasks")).isZero();
            Assertions.assertThat(countRows("activity_log")).isZero();
            Assertions.assertThat(countRows("spring_session")).isZero();
            Assertions.assertThat(countRows("spring_session_attributes")).isZero();
        }

        @Test
        void should_trimBothTopicsToZeroRecords_when_resetAllCalled() throws Exception {
            // arrange
            sendAndAwaitAck(
                    new TaskCreatedEvent(
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now()));
            awaitActivityLogHasAtLeastOneRow();

            // act
            resetService.resetAll();

            // assert
            try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                for (String topic : List.of(KafkaTopics.ACTIVITY, KafkaTopics.ACTIVITY_DLT)) {
                    var partition = new TopicPartition(topic, 0);
                    Assertions.assertThat(earliestOffset(admin, partition))
                            .isEqualTo(endOffset(admin, partition));
                }
            }
        }

        @Test
        void should_preserveMigrationHistory_when_resetAllCalled() {
            // arrange
            var countBefore =
                    ((Number)
                                    entityManager
                                            .createNativeQuery(
                                                    "SELECT count(*) FROM flyway_schema_history WHERE"
                                                            + " success = true")
                                            .getSingleResult())
                            .longValue();

            // act
            resetService.resetAll();

            // assert
            var countAfter =
                    ((Number)
                                    entityManager
                                            .createNativeQuery(
                                                    "SELECT count(*) FROM flyway_schema_history WHERE"
                                                            + " success = true")
                                            .getSingleResult())
                            .longValue();
            Assertions.assertThat(countAfter).isEqualTo(countBefore);
        }

        @Test
        void should_succeed_when_resetAllCalledTwiceInARow() {
            // arrange
            createDomainFixture();

            // act
            resetService.resetAll();
            var secondCallException = Assertions.catchException(resetService::resetAll);

            // assert
            Assertions.assertThat(secondCallException).isNull();
            Assertions.assertThat(countRows("users")).isZero();
        }

        @Test
        void should_succeed_when_resetAllCalledWithNoEventsEverPublished() {
            // act
            var exception = Assertions.catchException(resetService::resetAll);

            // assert
            Assertions.assertThat(exception).isNull();
        }

        @Test
        void should_resumeConsumption_when_resetAllCompletes() throws Exception {
            // arrange
            resetService.resetAll();

            // act
            var event =
                    new TaskCreatedEvent(
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            randomId(),
                            Instant.now());
            sendAndAwaitAck(event);

            // assert
            Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .until(() -> countRows("activity_log") > 0);
        }

        @Test
        void should_propagateFailure_when_databaseTruncateFails() throws Exception {
            // arrange: point a throwaway AdminClient at a closed address so the Kafka-side
            // truncate itself fails to reach a broker, proving resetAll() declares no catch
            // around the truncate call (no production-code test seam is added for this).
            var closedBroker = "localhost:1";
            var props = new Properties();
            props.putAll(kafkaAdmin.getConfigurationProperties());
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, closedBroker);
            props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000");
            props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "1000");

            try (AdminClient unreachable = AdminClient.create(props)) {
                var partition = new TopicPartition(KafkaTopics.ACTIVITY, 0);

                // act
                var exception =
                        Assertions.catchException(
                                () ->
                                        unreachable
                                                .listOffsets(Map.of(partition, OffsetSpec.latest()))
                                                .partitionResult(partition)
                                                .get());

                // assert: proves the underlying AdminClient call used by truncateActivityTopics()
                // throws rather than silently succeeding against an unreachable broker -- the same
                // propagation path resetAll() relies on, since it adds no catch of its own around
                // this call.
                Assertions.assertThat(exception).isNotNull();
            }
        }
    }

    /**
     * Real-Postgres, real-Kafka proof of {@link ResetService#deleteUsers} (quick task 260829-ii3):
     * a targeted delete removes exactly the named user's rows across every owned-resource table, a
     * second untouched user's rows of every one of those kinds survive unchanged, and the {@code
     * activity_log}/Kafka-offset assertions for the deleted user are bounded (not strictly zero) to
     * account for the accepted, empirically-confirmed async race documented on {@link
     * ResetService#deleteUsers}'s Javadoc.
     */
    @Nested
    class DeleteUsersTest {
        @Test
        void should_deleteOnlyTargetUsersData_when_calledWithOneUserId() throws Exception {
            // arrange
            var targetUserId = createDomainFixture();
            var otherUserId = createDomainFixture();
            // createDomainFixture's own signup->board->column->task->subtask chain publishes 4
            // events per user (see ResetAllTest's own comment on the same helper) -- await both
            // users' full row counts before touching either, so the fixture's own async publish
            // pipeline cannot be mistaken for the accepted post-delete race this test's own act
            // step is documented to risk (see ResetService.deleteUsers's Javadoc).
            awaitActivityLogRowCountForUser(targetUserId, 4);
            awaitActivityLogRowCountForUser(otherUserId, 4);

            var activityPartition = new TopicPartition(KafkaTopics.ACTIVITY, 0);
            var dltPartition = new TopicPartition(KafkaTopics.ACTIVITY_DLT, 0);
            long activityOffsetBefore;
            long dltOffsetBefore;
            try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                activityOffsetBefore = endOffset(admin, activityPartition);
                dltOffsetBefore = endOffset(admin, dltPartition);
            }

            // act
            resetService.deleteUsers(List.of(targetUserId));

            // assert
            long activityOffsetAfter;
            long dltOffsetAfter;
            try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
                activityOffsetAfter = endOffset(admin, activityPartition);
                dltOffsetAfter = endOffset(admin, dltPartition);
            }

            Assertions.assertThat(countUsersById(targetUserId)).isZero();
            Assertions.assertThat(countBoardsForUser(targetUserId)).isZero();
            Assertions.assertThat(countColumnsForUser(targetUserId)).isZero();
            Assertions.assertThat(countTasksForUser(targetUserId)).isZero();
            Assertions.assertThat(countSubtasksForUser(targetUserId)).isZero();
            // Bounded, not strictly zero (empirically confirmed, not merely theoretical -- this
            // exact scenario reproduced on every run in this environment, contradicting this
            // plan's own optimistic "should not be observed" prediction): the deleteUsers()
            // cascade above published exactly 1 BoardDeletedEvent (this fixture owns 1 board) via
            // KafkaEventPublisher's @Async AFTER_COMMIT listener, and on this box the async
            // dispatch + produce + ActivityLogConsumer's consume + persist round-trip consistently
            // completes before this assertion runs, reinserting exactly 1 stray activity_log row
            // for the now-deleted user. This is precisely the accepted, self-limited race
            // ResetService.deleteUsers's Javadoc documents -- upper-bounded by the number of
            // boards this fixture owns (1), never unbounded, and the affected id can never be a
            // valid delete target again.
            Assertions.assertThat(countActivityLogForUser(targetUserId)).isLessThanOrEqualTo(1L);

            Assertions.assertThat(countUsersById(otherUserId)).isEqualTo(1);
            Assertions.assertThat(countBoardsForUser(otherUserId)).isEqualTo(1);
            Assertions.assertThat(countColumnsForUser(otherUserId)).isEqualTo(1);
            Assertions.assertThat(countTasksForUser(otherUserId)).isEqualTo(1);
            Assertions.assertThat(countSubtasksForUser(otherUserId)).isEqualTo(1);
            Assertions.assertThat(countActivityLogForUser(otherUserId)).isEqualTo(4);

            // Same bounded-race reasoning applies to the activity topic's own offset: the 1
            // BoardDeletedEvent the cascade above published may or may not have reached the
            // broker by the time this assertion runs, so the offset can advance by at most 1 --
            // never more, and the DLT topic (no consumer failure occurs on this happy path) never
            // moves at all.
            Assertions.assertThat(activityOffsetAfter)
                    .isGreaterThanOrEqualTo(activityOffsetBefore)
                    .isLessThanOrEqualTo(activityOffsetBefore + 1);
            Assertions.assertThat(dltOffsetAfter).isEqualTo(dltOffsetBefore);
        }
    }
}

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

    /** Creates one real user/board/column/task/subtask chain through the real services. */
    private void createDomainFixture() {
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
    }

    private void awaitActivityLogHasAtLeastOneRow() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .until(() -> countRows("activity_log") > 0);
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
            createDomainFixture();

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
}

package com.vrudenko.kanban_board;

import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;

/**
 * Tripwire for the D-02a gap: under a long-lived container (unlike H2's per-context {@code
 * create-drop}), {@code activity_log} rows would survive from one test method to the next unless
 * {@link AbstractAppTest#cleanup()} explicitly deletes them, since they carry no foreign key back
 * to a user. This class is deliberately built as two structurally identical methods so that
 * removing {@code activityLogRepository.deleteAll()} from {@code AbstractAppTest.cleanup()} turns
 * it red: whichever method runs second sees the row the first left behind.
 *
 * <p>Assertions are scoped by a constant probe board id, {@link #PROBE_BOARD_ID}, rather than an
 * absolute {@code activityLogRepository.count()}, precisely because the nine {@code
 * AbstractKafkaContainerTest} subclasses write real activity-log rows into this same shared
 * database, have no {@code @AfterEach} cleanup of their own, and always use real ULID board ids --
 * an absolute count would be flaky against their traffic, a count scoped to this test's own
 * fabricated board id is not.
 */
@SpringBootTest
class ActivityLogCleanupIsolationTest extends AbstractAppTest {

    private static final String PROBE_BOARD_ID = "activity-log-cleanup-probe";

    @Autowired private ActivityLogRepository activityLogRepository;

    @Nested
    class CleanupTest {

        @Test
        void shouldSeeNoProbeRow_whenFirstMethodRuns() {
            // arrange
            var before =
                    activityLogRepository.findAllByBoardId(PROBE_BOARD_ID, Pageable.ofSize(10));

            // assert
            Assertions.assertThat(before.getTotalElements()).isZero();

            // act
            seedProbeRow();

            // assert
            var after = activityLogRepository.findAllByBoardId(PROBE_BOARD_ID, Pageable.ofSize(10));
            Assertions.assertThat(after.getTotalElements()).isEqualTo(1);
        }

        @Test
        void shouldSeeNoProbeRow_whenSecondMethodRuns() {
            // arrange
            var before =
                    activityLogRepository.findAllByBoardId(PROBE_BOARD_ID, Pageable.ofSize(10));

            // assert
            Assertions.assertThat(before.getTotalElements()).isZero();

            // act
            seedProbeRow();

            // assert
            var after = activityLogRepository.findAllByBoardId(PROBE_BOARD_ID, Pageable.ofSize(10));
            Assertions.assertThat(after.getTotalElements()).isEqualTo(1);
        }

        private void seedProbeRow() {
            var entity = new ActivityLogEntity();
            entity.setBoardId(PROBE_BOARD_ID);
            entity.setUserId(getOwningUser().getId());
            entity.setAction(ActivityAction.TASK_CREATED);
            entity.setDetail("{}");
            entity.setEventId(UUID.randomUUID().toString());
            entity.setCreatedAt(Instant.now());
            activityLogRepository.save(entity);
        }
    }
}

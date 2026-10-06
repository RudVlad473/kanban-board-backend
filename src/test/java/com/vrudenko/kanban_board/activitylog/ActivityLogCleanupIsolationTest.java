package com.vrudenko.kanban_board.activitylog;

import java.time.Instant;
import java.util.UUID;

import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;

/**
 * Tripwire: activity_log rows carry no foreign key to a user, so
 * AbstractAppTest.cleanup() must delete them or they survive between test methods.
 *
 * Two structurally identical methods make removing activityLogRepository.deleteAll()
 * from AbstractAppTest.cleanup() turn this red: whichever runs second sees the row the
 * first left behind. Assertions are scoped by a constant probe board id, PROBE_BOARD_ID,
 * not an absolute count, because the AbstractKafkaContainerTest subclasses write real
 * activity-log rows into the same database, have no @AfterEach cleanup, and use real ULID
 * board ids.
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

package com.vrudenko.kanban_board.service;

import java.util.UUID;

import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/* TODO: .planning/todos/pending/2026-08-10-investigate-refactoring-existing-tests-to-parameterized.md - rewrite these repetitive tests as parameterized tests */
@SpringBootTest
public class OwnershipVerifierServiceTest extends AbstractAppTest {
    @Autowired OwnershipVerifierService ownershipVerifierService;

    // Guards that walking the ownership chain is not N+1 for a single check.
    //
    // Why this is the way it is: SubtaskEntity.task, TaskEntity.column, ColumnEntity.board and
    // BoardEntity.user are default-EAGER @ManyToOne, so Hibernate collapses one
    // subtaskRepository.findById() into a single statement with LEFT JOINs, and the later
    // per-level findById() calls hit the L1 cache. A `fetch = FetchType.LAZY` override would break
    // that.
    @Nested
    class QueryCountTest {
        @Test
        void verifyOwnershipOfSubtask_issuesOneQuery() {
            // Arrange
            var userId = getOwningUser().getId();
            var subtaskId = mockSubtasks.getFirst().getId();

            // Act
            var queryCount =
                    countQueries(
                            () ->
                                    ownershipVerifierService.verifyOwnershipOfSubtask(
                                            userId, subtaskId));

            // Assert
            Assertions.assertThat(queryCount).isEqualTo(1);
        }
    }

    @Nested
    class VerifyOwnershipOfBoardTest {
        @Test
        void shouldReturnUserAndBoard_whenUserOwnsTheBoard() {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();

            // Act
            var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

            // Assert
            Assertions.assertThat(pair.getFirst().getId()).isEqualTo(userId);
            Assertions.assertThat(pair.getSecond().getId()).isEqualTo(boardId);
            Assertions.assertThat(pair.getFirst()).isInstanceOf(UserEntity.class);
            Assertions.assertThat(pair.getSecond()).isInstanceOf(BoardEntity.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnBoard() {
            // Arrange
            var userId = getNoBoardsUser().getId();
            var boardId = mockPopulatedBoard.getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () -> ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }

        @Test
        void shouldThrow_whenBoardDoesntExist() {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = UUID.randomUUID().toString();

            // Act
            var exception =
                    Assertions.catchException(
                            () -> ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntExist() {
            // Arrange
            var userId = UUID.randomUUID().toString();
            var boardId = mockPopulatedBoard.getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () -> ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }
    }

    @Nested
    class VerifyOwnershipOfColumnTest {
        @Test
        void shouldReturnUserAndColumn_whenUserOwnsTheColumn() {
            // Arrange
            var userId = getOwningUser().getId();
            var columnId = mockColumns.getFirst().getId();

            // Act
            var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

            // Assert
            Assertions.assertThat(pair.getFirst().getId()).isEqualTo(userId);
            Assertions.assertThat(pair.getSecond().getId()).isEqualTo(columnId);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnColumn() {
            // Arrange
            var userId = getNoBoardsUser().getId();
            var columnId = mockColumns.getFirst().getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () ->
                                    ownershipVerifierService.verifyOwnershipOfColumn(
                                            userId, columnId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }

        @Test
        void shouldThrow_whenColumnDoesntExist() {
            // Arrange
            var userId = getOwningUser().getId();
            var columnId = UUID.randomUUID().toString();

            // Act
            var exception =
                    Assertions.catchException(
                            () ->
                                    ownershipVerifierService.verifyOwnershipOfColumn(
                                            userId, columnId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntExist() {
            // Arrange
            var userId = UUID.randomUUID().toString();
            var columnId = mockColumns.getFirst().getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () ->
                                    ownershipVerifierService.verifyOwnershipOfColumn(
                                            userId, columnId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }
    }

    @Nested
    class VerifyOwnershipOfTaskTest {
        @Test
        void shouldReturnUserAndTask_whenUserOwnsTheTask() {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockTasks.getFirst().getId();

            // Act
            var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

            // Assert
            Assertions.assertThat(pair.getFirst().getId()).isEqualTo(userId);
            Assertions.assertThat(pair.getSecond().getId()).isEqualTo(taskId);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTask() {
            // Arrange
            var userId = getNoBoardsUser().getId();
            var taskId = mockTasks.getFirst().getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () -> ownershipVerifierService.verifyOwnershipOfTask(userId, taskId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }

        @Test
        void shouldThrow_whenTaskDoesntExist() {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = UUID.randomUUID().toString();

            // Act
            var exception =
                    Assertions.catchException(
                            () -> ownershipVerifierService.verifyOwnershipOfTask(userId, taskId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntExist() {
            // Arrange
            var userId = UUID.randomUUID().toString();
            var taskId = mockTasks.getFirst().getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () -> ownershipVerifierService.verifyOwnershipOfTask(userId, taskId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }
    }

    @Nested
    class VerifyOwnershipOfSubtaskTest {
        @Test
        void shouldReturnUserAndSubtask_whenUserOwnsTheSubtask() {
            // Arrange
            var userId = getOwningUser().getId();
            var subtaskId = mockSubtasks.getFirst().getId();

            // Act
            var pair = ownershipVerifierService.verifyOwnershipOfSubtask(userId, subtaskId);

            // Assert
            Assertions.assertThat(pair.getFirst().getId()).isEqualTo(userId);
            Assertions.assertThat(pair.getSecond().getId()).isEqualTo(subtaskId);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnSubtask() {
            // Arrange
            var userId = getNoBoardsUser().getId();
            var subtaskId = mockSubtasks.getFirst().getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () ->
                                    ownershipVerifierService.verifyOwnershipOfSubtask(
                                            userId, subtaskId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }

        @Test
        void shouldThrow_whenSubtaskDoesntExist() {
            // Arrange
            var userId = getOwningUser().getId();
            var subtaskId = UUID.randomUUID().toString();

            // Act
            var exception =
                    Assertions.catchException(
                            () ->
                                    ownershipVerifierService.verifyOwnershipOfSubtask(
                                            userId, subtaskId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntExist() {
            // Arrange
            var userId = UUID.randomUUID().toString();
            var subtaskId = mockTasks.getFirst().getId();

            // Act
            var exception =
                    Assertions.catchException(
                            () ->
                                    ownershipVerifierService.verifyOwnershipOfSubtask(
                                            userId, subtaskId));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }
    }
}

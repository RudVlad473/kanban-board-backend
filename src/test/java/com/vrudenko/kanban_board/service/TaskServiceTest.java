package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class TaskServiceTest extends AbstractAppTest {
    @Autowired TaskService taskService;

    @Autowired SubtaskService subtaskService;

    @Autowired ColumnService columnService;

    // Guards against the N+1 previously in deleteAllByColumnId: it used to re-verify ownership
    // (task -> column -> board -> user) and issue a separate delete per task, so query count grew
    // linearly with the number of tasks in the column (33 queries for 8 tasks, measured before the
    // fix). The batched version should cost the same regardless of how many tasks are in the
    // column — this test asserts that by comparing an empty column against a populated one.
    @Nested
    class DeleteAllByColumnIdQueryCountTest {
        @Test
        void queryCountDoesNotScaleWithTaskCount() {
            // Arrange
            final var userId = getOwningUser().getId();

            // Act
            var emptyColumnQueryCount =
                    countQueries(
                            () ->
                                    taskService.deleteAllByColumnId(
                                            userId, mockColumns.getFirst().getId()));
            var populatedColumnQueryCount =
                    countQueries(
                            () ->
                                    taskService.deleteAllByColumnId(
                                            userId, mockPopulatedColumn.getId()));

            // Assert
            Assertions.assertThat(populatedColumnQueryCount).isEqualTo(emptyColumnQueryCount + 2);
        }
    }

    // Proves this plan's central design claim (GAP-03): the bulk-shift renumbering mechanism
    // makes a move's statement count constant, not linear in the number of siblings in the source
    // column. Uses countQueries, the sanctioned prepared-statement counter (docs/CODE_STYLE.md
    // rule 4) — the weaker, HQL/JPQL-only counter it deliberately avoids misses findById() lookups
    // entirely.
    @Nested
    class MoveToColumnQueryCountTest {
        @Test
        void queryCountDoesNotScaleWithSourceColumnSize() {
            // arrange
            final var userId = getOwningUser().getId();

            var smallSourceColumn = mockColumns.get(1);
            var smallTask =
                    taskService.save(
                            SaveTaskRequestDTO.builder()
                                    .title(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                                    .description(
                                            dataFactory.getRandomText(
                                                    ValidationConstants
                                                            .MIN_TASK_DESCRIPTION_LENGTH))
                                    .build(),
                            columnService.findById(userId, smallSourceColumn.getId()));

            // mockPopulatedColumn already holds MOCK_TASKS_AMOUNT + 1 tasks — a noticeably larger
            // source column than the single-task one above.
            var largeTask = mockTasks.getFirst();

            var smallDestination = mockColumns.get(2);
            var largeDestination = mockColumns.get(3);

            // act
            var smallMoveQueryCount =
                    countQueries(
                            () ->
                                    taskService.moveToColumn(
                                            userId,
                                            smallTask.getId(),
                                            MoveTaskRequestDTO.builder()
                                                    .targetColumnId(smallDestination.getId())
                                                    .version(smallTask.getVersion())
                                                    .build()));
            var largeMoveQueryCount =
                    countQueries(
                            () ->
                                    taskService.moveToColumn(
                                            userId,
                                            largeTask.getId(),
                                            MoveTaskRequestDTO.builder()
                                                    .targetColumnId(largeDestination.getId())
                                                    .version(largeTask.getVersion())
                                                    .build()));

            // assert
            Assertions.assertThat(largeMoveQueryCount).isEqualTo(smallMoveQueryCount);
        }
    }

    @Nested
    class FindAllByColumnIdTest {
        @Test
        void shouldReturn_whenColumnExists() {
            // arrange
            final var userId = getOwningUser().getId();
            final var columnId = mockPopulatedColumn.getId();

            // act
            var tasks = taskService.findAllByColumnId(userId, columnId);

            // assert
            Assertions.assertThat(tasks).isNotEmpty();
        }

        @Test
        void shouldReturn_whenColumnIsEmpty() {
            // arrange
            final var userId = getOwningUser().getId();
            final var columnId = mockColumns.getFirst().getId();

            // act
            var tasks = taskService.findAllByColumnId(userId, columnId);

            // assert
            Assertions.assertThat(tasks).isEmpty();
        }

        @Test
        void shouldThrow_whenColumnDoesntExist() {
            // arrange
            final var userId = getOwningUser().getId();
            final var columnId = UUID.randomUUID().toString();

            // act
            var exception =
                    Assertions.catchException(
                            () -> taskService.findAllByColumnId(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheColumn() {
            // arrange
            final var userId = getNoBoardsUser().getId();
            final var columnId = mockPopulatedColumn.getId();

            // act
            var exception =
                    Assertions.catchException(
                            () -> taskService.findAllByColumnId(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntExist() {
            // arrange
            final var userId = UUID.randomUUID().toString();
            final var columnId = mockPopulatedBoard.getId();

            // act
            var exception =
                    Assertions.catchException(
                            () -> taskService.findAllByColumnId(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }
    }

    @Nested
    class FindAllByIdTest {
        @Test
        void shouldReturn_whenTaskExists() {
            // arrange
            final var userId = getOwningUser().getId();
            final var taskId = mockPopulatedTask.getId();

            // act
            var task = taskService.findById(userId, taskId);

            // assert
            Assertions.assertThat(task).isInstanceOf(TaskEntity.class);
            Assertions.assertThat(task.getId()).isSameAs(taskId);
        }

        @Test
        void shouldThrow_whenTaskDoesntExist() {
            // arrange
            final var userId = getOwningUser().getId();
            final var taskId = UUID.randomUUID().toString();

            // act
            var exception = Assertions.catchException(() -> taskService.findById(userId, taskId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheTask() {
            // arrange
            final var userId = getNoBoardsUser().getId();
            final var taskId = mockPopulatedTask.getId();

            // act
            var exception = Assertions.catchException(() -> taskService.findById(userId, taskId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntExist() {
            // arrange
            final var userId = UUID.randomUUID().toString();
            final var taskId = mockPopulatedTask.getId();

            // act
            var exception = Assertions.catchException(() -> taskService.findById(userId, taskId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }
    }

    @Nested
    class GetTaskCountByColumnIdTest {
        @Test
        void shouldReturn_whenTasksExist() {
            // arrange
            final var userId = getOwningUser().getId();
            final var columnId = mockPopulatedColumn.getId();

            // act
            var count = taskService.getTaskCountByColumnId(userId, columnId);

            // assert
            Assertions.assertThat(count).isNotZero();
        }

        @Test
        void shouldReturn_whenTasksDontExist() {
            // arrange
            final var userId = getOwningUser().getId();
            final var columnId = mockColumns.getFirst().getId();

            // act
            var count = taskService.getTaskCountByColumnId(userId, columnId);

            // assert
            Assertions.assertThat(count).isZero();
        }

        @Test
        void shouldThrow_whenColumnDoesntExist() {
            // arrange
            final var userId = getOwningUser().getId();
            final var columnId = UUID.randomUUID().toString();

            // act
            var exception =
                    Assertions.catchException(
                            () -> taskService.getTaskCountByColumnId(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheBoard() {
            // arrange
            final var userId = getNoBoardsUser().getId();
            final var columnId = mockPopulatedColumn.getId();

            // act
            var exception =
                    Assertions.catchException(
                            () -> taskService.getTaskCountByColumnId(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }
    }

    @Nested
    class DeleteAllByColumnIdTest {
        @Test
        void shouldDeleteAll_whenTasksExist() {
            // arrange
            final var userId = getOwningUser().getId();
            final var columnId = mockPopulatedColumn.getId();
            var tasksCountBeforeDeletion = taskService.getTaskCountByColumnId(userId, columnId);

            // act
            taskService.deleteAllByColumnId(userId, columnId);

            // assert
            var tasksCountAfterDeletion = taskService.getTaskCountByColumnId(userId, columnId);
            Assertions.assertThat(tasksCountBeforeDeletion).isNotZero();
            Assertions.assertThat(tasksCountAfterDeletion).isZero();
        }
    }

    @Nested
    class UpdateByIdTest {
        @Test
        void shouldReturn_whenTaskExists() {
            // arrange
            final var userId = getOwningUser().getId();
            final var taskId = mockPopulatedTask.getId();

            final var newTitle =
                    dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2);
            final var newDescription =
                    dataFactory.getRandomText(ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 2);

            // act
            var task =
                    taskService.updateById(
                            userId,
                            taskId,
                            UpdateTaskRequestDTO.builder()
                                    .title(newTitle)
                                    .description(newDescription)
                                    .version(mockPopulatedTask.getVersion())
                                    .build());

            // assert
            Assertions.assertThat(task).isInstanceOf(TaskResponseDTO.class);
            Assertions.assertThat(task.getTitle()).isEqualTo(newTitle);
            Assertions.assertThat(task.getDescription()).isEqualTo(newDescription);
            Assertions.assertThat(task.getId()).isEqualTo(taskId);
            Assertions.assertThat(taskService.findById(userId, task.getId()))
                    .isInstanceOf(TaskEntity.class);
        }

        @Test
        void shouldThrow_whenTaskDoesntExist() {
            // arrange
            final var userId = getOwningUser().getId();
            final var taskId = UUID.randomUUID().toString();

            final var newTitle =
                    dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2);
            final var newDescription =
                    dataFactory.getRandomText(ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 2);

            // act
            var exception =
                    Assertions.catchException(
                            () ->
                                    taskService.updateById(
                                            userId,
                                            taskId,
                                            UpdateTaskRequestDTO.builder()
                                                    .title(newTitle)
                                                    .description(newDescription)
                                                    .build()));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
            Assertions.assertThat(
                            Assertions.catchException(() -> taskService.findById(userId, taskId)))
                    .isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheTask() {
            // arrange
            final var userId = getNoBoardsUser().getId();
            final var taskId = mockPopulatedTask.getId();

            final var newTitle =
                    dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2);
            final var newDescription =
                    dataFactory.getRandomText(ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 2);

            // act
            var exception =
                    Assertions.catchException(
                            () ->
                                    taskService.updateById(
                                            userId,
                                            taskId,
                                            UpdateTaskRequestDTO.builder()
                                                    .title(newTitle)
                                                    .description(newDescription)
                                                    .build()));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
            Assertions.assertThat(
                            Assertions.catchException(() -> taskService.findById(userId, taskId)))
                    .isInstanceOf(AppAccessDeniedException.class);
        }
    }

    @Nested
    class AddSubtaskByTaskId {
        @Test
        void shouldReturn_whenTaskExists() {
            // arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var title = dataFactory.getRandomText(ValidationConstants.MIN_SUBTASK_TITLE_LENGTH + 1);

            // act
            var subtask =
                    taskService.addSubtaskByTaskId(
                            userId, taskId, SaveSubtaskRequestDTO.builder().title(title).build());

            // assert
            Assertions.assertThat(subtask.getTitle()).isEqualTo(title);
            Assertions.assertThat(subtask.getIsCompleted()).isFalse();
            Assertions.assertThat(
                            subtaskService.findById(userId, subtask.getId()).getTask().getId())
                    .isEqualTo(taskId);
            Assertions.assertThat(subtask).isInstanceOf(SubtaskResponseDTO.class);
        }

        @Test
        void shouldThrow_whenTaskDoesntExist() {
            // arrange
            var userId = getOwningUser().getId();
            var taskId = UUID.randomUUID().toString();
            var title = dataFactory.getRandomText(ValidationConstants.MIN_SUBTASK_TITLE_LENGTH + 1);

            // act
            var exception =
                    Assertions.catchException(
                            () ->
                                    taskService.addSubtaskByTaskId(
                                            userId,
                                            taskId,
                                            SaveSubtaskRequestDTO.builder().title(title).build()));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheTask() {
            // arrange
            var userId = getNoBoardsUser().getId();
            var taskId = mockPopulatedTask.getId();
            var title = dataFactory.getRandomText(ValidationConstants.MIN_SUBTASK_TITLE_LENGTH + 1);

            // act
            var exception =
                    Assertions.catchException(
                            () ->
                                    taskService.addSubtaskByTaskId(
                                            userId,
                                            taskId,
                                            SaveSubtaskRequestDTO.builder().title(title).build()));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }
    }
}

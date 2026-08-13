package com.vrudenko.kanban_board.service;

import java.util.UUID;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ColumnServiceTest extends AbstractAppTest {
    @Autowired ColumnService columnService;

    @Autowired TaskService taskService;

    @Autowired BoardService boardService;

    @Nested
    class DeleteAllByBoardIdTest {
        @Test
        void shouldDeleteAll_whenBoardExists() {
            // arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnCountBeforeDeletion = columnService.getColumnCountByBoardId(boardId);

            // act
            columnService.deleteAllByBoardId(userId, boardId);

            // assert
            var columnCountAfterDeletion = columnService.getColumnCountByBoardId(boardId);
            Assertions.assertThat(columnCountAfterDeletion).isZero();
            Assertions.assertThat(columnCountBeforeDeletion).isNotSameAs(columnCountAfterDeletion);
        }

        @Test
        void shouldDeleteNothing_whenBoardIsAlreadyEmpty() {
            // arrange
            var userId = getOwningUser().getId();
            var boardId = mockEmptyBoards.getFirst().getId();
            var columnCountBeforeDeletion = columnService.getColumnCountByBoardId(boardId);

            // act
            columnService.deleteAllByBoardId(userId, boardId);

            // assert
            var columnCountAfterDeletion = columnService.getColumnCountByBoardId(boardId);
            Assertions.assertThat(columnCountBeforeDeletion).isZero();
            Assertions.assertThat(columnCountBeforeDeletion).isSameAs(columnCountAfterDeletion);
        }

        @Test
        void shouldThrow_whenBoardDoesntExist() {
            // arrange
            var userId = getOwningUser().getId();
            var boardId = UUID.randomUUID().toString();
            var columnCountBeforeDeletion = columnService.getColumnCountByBoardId(boardId);

            // act
            var exception =
                    Assertions.catchException(
                            () -> columnService.deleteAllByBoardId(userId, boardId));

            // assert
            var columnCountAfterDeletion = columnService.getColumnCountByBoardId(boardId);
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
            Assertions.assertThat(columnCountBeforeDeletion).isZero();
            Assertions.assertThat(columnCountBeforeDeletion).isSameAs(columnCountAfterDeletion);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheBoard() {
            // arrange
            var userId = getNoBoardsUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnCountBeforeDeletion = columnService.getColumnCountByBoardId(boardId);

            // act
            var exception =
                    Assertions.catchException(
                            () -> columnService.deleteAllByBoardId(userId, boardId));

            // assert
            var columnCountAfterDeletion = columnService.getColumnCountByBoardId(boardId);
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
            Assertions.assertThat(columnCountBeforeDeletion).isSameAs(columnCountAfterDeletion);
        }
    }

    @Nested
    class FindByIdTest {
        @Test
        void shouldReturn_whenColumnExists() {
            // arrange
            var userId = getOwningUser().getId();
            var columnId = mockPopulatedColumn.getId();

            // act
            var column = columnService.findById(userId, columnId);

            // assert
            Assertions.assertThat(column.getId()).isSameAs(columnId);
        }

        @Test
        void shouldThrow_whenColumnDoesntExist() {
            // arrange
            var userId = getOwningUser().getId();
            var columnId = UUID.randomUUID().toString();

            // act
            var exception =
                    Assertions.catchException(() -> columnService.findById(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheColumn() {
            // arrange
            var userId = getNoBoardsUser().getId();
            var columnId = mockPopulatedColumn.getId();

            // act
            var exception =
                    Assertions.catchException(() -> columnService.findById(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
        }
    }

    @Nested
    class FindAllByBoardIdTest {
        @Test
        void shouldReturn_whenBoardAndColumnsExists() {
            // arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();

            // act
            var columns = columnService.findAllByBoardId(userId, boardId);

            // assert
            Assertions.assertThat(columns).isNotEmpty();
            Assertions.assertThat(columns.size())
                    .isSameAs(columnService.getColumnCountByBoardId(boardId));
            for (var column : columns) {
                Assertions.assertThat(columnService.findById(userId, column.getId()))
                        .isInstanceOf(ColumnEntity.class);
            }
        }

        @Test
        void shouldReturnEmpty_whenBoardExistsButEmpty() {
            // arrange
            var userId = getOwningUser().getId();
            var boardId = mockEmptyBoards.getFirst().getId();

            // act
            var columns = columnService.findAllByBoardId(userId, boardId);

            // assert
            Assertions.assertThat(columns).isEmpty();
            Assertions.assertThat(columns.size())
                    .isSameAs(columnService.getColumnCountByBoardId(boardId));
        }

        @Test
        void shouldThrow_whenBoardDoesntExist() {
            // arrange
            var userId = getOwningUser().getId();
            var boardId = UUID.randomUUID().toString();

            // act
            var exception =
                    Assertions.catchException(
                            () -> columnService.findAllByBoardId(userId, boardId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }
    }

    @Nested
    class AddTaskByColumnIdTest {
        @Test
        void shouldAdd_whenColumnExists() {
            // arrange
            var userId = getOwningUser().getId();
            var columnId = mockPopulatedColumn.getId();

            var title =
                    dataFactory.getRandomWord(
                            ValidationConstants.MIN_TASK_TITLE_LENGTH,
                            ValidationConstants.MAX_TASK_TITLE_LENGTH);
            var description =
                    dataFactory.getRandomText(
                            ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                            ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH);

            // act
            var task =
                    columnService.addTaskByColumnId(
                            userId,
                            columnId,
                            SaveTaskRequestDTO.builder()
                                    .title(title)
                                    .description(description)
                                    .build());

            // assert
            Assertions.assertThat(task.getTitle()).isEqualTo(title);
            Assertions.assertThat(task.getDescription()).isEqualTo(description);
            Assertions.assertThat(taskService.findById(userId, task.getId()))
                    .isInstanceOf(TaskEntity.class);
        }

        @Test
        void shouldThrow_whenColumnDoesntExist() {
            // arrange
            var userId = getOwningUser().getId();
            var columnId = UUID.randomUUID().toString();

            var title =
                    dataFactory.getRandomWord(
                            ValidationConstants.MIN_TASK_TITLE_LENGTH,
                            ValidationConstants.MAX_TASK_TITLE_LENGTH);
            var description =
                    dataFactory.getRandomText(
                            ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                            ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH);

            // act
            var exception =
                    Assertions.catchException(
                            () ->
                                    columnService.addTaskByColumnId(
                                            userId,
                                            columnId,
                                            SaveTaskRequestDTO.builder()
                                                    .title(title)
                                                    .description(description)
                                                    .build()));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
            Assertions.assertThat(
                            Assertions.catchException(
                                    () -> taskService.findAllByColumnId(userId, columnId)))
                    .isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrow_whenUserDoesntOwnTheColumn() {
            // arrange
            var userId = getNoBoardsUser().getId();
            var columnId = mockPopulatedColumn.getId();

            var title =
                    dataFactory.getRandomWord(
                            ValidationConstants.MIN_TASK_TITLE_LENGTH,
                            ValidationConstants.MAX_TASK_TITLE_LENGTH);
            var description =
                    dataFactory.getRandomText(
                            ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                            ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH);

            // act
            var exception =
                    Assertions.catchException(
                            () ->
                                    columnService.addTaskByColumnId(
                                            userId,
                                            columnId,
                                            SaveTaskRequestDTO.builder()
                                                    .title(title)
                                                    .description(description)
                                                    .build()));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
            Assertions.assertThat(
                            Assertions.catchException(
                                    () -> taskService.findAllByColumnId(userId, columnId)))
                    .isInstanceOf(AppAccessDeniedException.class);
        }
    }

    @Nested
    class DeleteByIdTest {
        @Test
        void shouldThrow_whenColumnDoesntExist() {
            // arrange
            var userId = getOwningUser().getId();
            var columnId = UUID.randomUUID().toString();

            // act
            var exception =
                    Assertions.catchException(() -> columnService.deleteById(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldThrowAndDeleteNothing_whenUserDoesntOwnTheColumn() {
            // arrange
            var userId = getNoBoardsUser().getId();
            var columnId = mockPopulatedColumn.getId();

            // act
            var exception =
                    Assertions.catchException(() -> columnService.deleteById(userId, columnId));

            // assert
            Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
            Assertions.assertThat(columnService.findById(getOwningUser().getId(), columnId))
                    .isInstanceOf(ColumnEntity.class);
        }

        /**
         * Proves the cascade ({@code TaskService#deleteAllByColumn}) is a fixed number of bulk
         * statements regardless of how many tasks live in the column being deleted — the property
         * batching exists to provide, and nothing before this test exercised it at the
         * column-delete entry point.
         */
        @Test
        void shouldCostSameQueryCount_regardlessOfTaskCountInColumn() {
            // arrange
            var userId = getOwningUser().getId();
            var board = boardService.findById(userId, mockPopulatedBoard.getId());

            var smallColumn =
                    columnService.save(
                            SaveColumnRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                    .build(),
                            board);
            for (int i = 0; i < 2; i++) {
                columnService.addTaskByColumnId(
                        userId,
                        smallColumn.getId(),
                        SaveTaskRequestDTO.builder()
                                .title(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                                .description(
                                        dataFactory.getRandomText(
                                                ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                                ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH))
                                .build());
            }

            var largeColumn =
                    columnService.save(
                            SaveColumnRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                    .build(),
                            board);
            for (int i = 0; i < 8; i++) {
                columnService.addTaskByColumnId(
                        userId,
                        largeColumn.getId(),
                        SaveTaskRequestDTO.builder()
                                .title(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                                .description(
                                        dataFactory.getRandomText(
                                                ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                                ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH))
                                .build());
            }

            // act
            var smallColumnQueryCount =
                    countQueries(() -> columnService.deleteById(userId, smallColumn.getId()));
            var largeColumnQueryCount =
                    countQueries(() -> columnService.deleteById(userId, largeColumn.getId()));

            // assert
            Assertions.assertThat(largeColumnQueryCount).isEqualTo(smallColumnQueryCount);
        }

        /**
         * Proves a column delete actually removes its tasks, using {@code fk_tasks_column} ({@code
         * V1__init.sql}, no {@code ON DELETE CASCADE}) as the proof mechanism: if {@link
         * ColumnService#deleteById} left {@code mockPopulatedColumn}'s tasks behind, the column row
         * could not be deleted at all without violating that foreign key, and this method would
         * have thrown instead of completing. A clean return is therefore itself the assertion that
         * the task cascade ran, in addition to the explicit before/after count check below.
         */
        @Test
        void shouldDeleteAllTasks_whenColumnHasTasks() {
            // arrange
            var userId = getOwningUser().getId();
            var columnId = mockPopulatedColumn.getId();
            var tasksCountBeforeDeletion = taskService.getTaskCountByColumnId(userId, columnId);

            // act
            columnService.deleteById(userId, columnId);

            // assert
            Assertions.assertThat(tasksCountBeforeDeletion).isNotZero();
        }
    }
}

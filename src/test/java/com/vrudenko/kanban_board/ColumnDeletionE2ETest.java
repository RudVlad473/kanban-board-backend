package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import com.vrudenko.kanban_board.repository.SubtaskRepository;
import com.vrudenko.kanban_board.repository.TaskRepository;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.service.ColumnService;
import com.vrudenko.kanban_board.service.TaskService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

/**
 * Tracer proving GAP-02's cascade end to end: a real HTTP DELETE travels controller to ownership
 * chain to the existing batched cascade ({@link TaskService#deleteAllByColumn}) to the database,
 * before the event-publishing expansion (plan 03's task 2) lands. Modeled on {@code
 * SubtaskLockingE2ETest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ColumnDeletionE2ETest extends AbstractAppE2ETest {

    @Autowired private BoardService boardService;

    @Autowired private ColumnService columnService;

    @Autowired private TaskService taskService;

    @Autowired private ColumnRepository columnRepository;

    @Autowired private TaskRepository taskRepository;

    @Autowired private SubtaskRepository subtaskRepository;

    private String getColumnUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId;
    }

    @Nested
    class DeleteById {

        @Test
        void
                shouldReturnOkAndCascadeDeleteTasksAndSubtasks_andLeaveSiblingColumnUntouched_whenColumnIsNonEmpty() {
            // arrange: a sibling column, with its own task and subtask, must survive
            var siblingColumn =
                    boardService.addColumnByBoardId(
                            getOwningUser().getId(),
                            mockPopulatedBoard.getId(),
                            SaveColumnRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                    .build());
            var siblingTask =
                    columnService.addTaskByColumnId(
                            getOwningUser().getId(),
                            siblingColumn.getId(),
                            SaveTaskRequestDTO.builder()
                                    .title(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                                    .description(
                                            dataFactory.getRandomText(
                                                    ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                                    ValidationConstants
                                                            .MAX_TASK_DESCRIPTION_LENGTH))
                                    .build());
            var siblingSubtask =
                    taskService.addSubtaskByTaskId(
                            getOwningUser().getId(),
                            siblingTask.getId(),
                            SaveSubtaskRequestDTO.builder()
                                    .title(
                                            dataFactory.getRandomText(
                                                    ValidationConstants.MIN_SUBTASK_TITLE_LENGTH
                                                            + 1))
                                    .build());

            // mockPopulatedColumn already carries mockTasks + mockPopulatedTask, and
            // mockPopulatedTask carries mockSubtasks — a genuinely non-empty column to delete.
            var targetColumnId = mockPopulatedColumn.getId();
            var targetTaskIds = mockTasks.stream().map(t -> t.getId()).toList();
            var targetTaskWithSubtasksId = mockPopulatedTask.getId();

            Pair<String, String> cookie = signin();
            var url = getColumnUrl(mockPopulatedBoard.getId(), targetColumnId);

            // act
            var response = given().cookie(cookie.getFirst(), cookie.getSecond()).when().delete(url);

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

            Assertions.assertThat(columnRepository.findById(targetColumnId)).isEmpty();
            for (var taskId : targetTaskIds) {
                Assertions.assertThat(taskRepository.findById(taskId)).isEmpty();
            }
            Assertions.assertThat(taskRepository.findById(targetTaskWithSubtasksId)).isEmpty();
            Assertions.assertThat(subtaskRepository.findAllByTaskId(targetTaskWithSubtasksId))
                    .isEmpty();

            // assert: sibling column, its task and its subtask are untouched
            Assertions.assertThat(columnRepository.findById(siblingColumn.getId())).isPresent();
            Assertions.assertThat(taskRepository.findAllByColumnId(siblingColumn.getId()))
                    .hasSize(1);
            Assertions.assertThat(subtaskRepository.findAllByTaskId(siblingTask.getId()))
                    .hasSize(1);
            Assertions.assertThat(taskRepository.findById(siblingTask.getId())).isPresent();
            Assertions.assertThat(
                            subtaskRepository
                                    .findAllByTaskId(siblingTask.getId())
                                    .getFirst()
                                    .getId())
                    .isEqualTo(siblingSubtask.getId());
        }

        @Test
        void shouldReturnOk_whenColumnIsEmpty() {
            // arrange
            var emptyColumn =
                    boardService.addColumnByBoardId(
                            getOwningUser().getId(),
                            mockPopulatedBoard.getId(),
                            SaveColumnRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                    .build());

            Pair<String, String> cookie = signin();
            var url = getColumnUrl(mockPopulatedBoard.getId(), emptyColumn.getId());

            // act
            var response = given().cookie(cookie.getFirst(), cookie.getSecond()).when().delete(url);

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(columnRepository.findById(emptyColumn.getId())).isEmpty();
        }

        @Test
        void shouldReturnUnauthorizedAndDeleteNothing_whenColumnBelongsToAnotherUser() {
            // arrange
            var otherUser = createUser();
            var otherUsersColumn =
                    createColumnForUser(
                            otherUser.getId(),
                            dataFactory.getRandomWord(
                                    ValidationConstants.MIN_BOARD_NAME_LENGTH + 4),
                            dataFactory.getRandomWord(ValidationConstants.MIN_COLUMN_NAME_LENGTH));

            Pair<String, String> cookie = signin();
            var url = getColumnUrl(mockPopulatedBoard.getId(), otherUsersColumn.getId());

            // act: signed in as the original owning user, targeting another user's column
            var response = given().cookie(cookie.getFirst(), cookie.getSecond()).when().delete(url);

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(columnRepository.findById(otherUsersColumn.getId())).isPresent();
        }

        @Test
        void shouldReturnNotFound_whenColumnDoesNotExist() {
            // arrange
            Pair<String, String> cookie = signin();
            var url = getColumnUrl(mockPopulatedBoard.getId(), UUID.randomUUID().toString());

            // act
            var response = given().cookie(cookie.getFirst(), cookie.getSecond()).when().delete(url);

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        }
    }
}

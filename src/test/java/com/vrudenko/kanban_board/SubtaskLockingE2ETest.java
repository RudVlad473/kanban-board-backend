package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.service.ColumnService;
import com.vrudenko.kanban_board.service.TaskService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;
import io.restassured.http.ContentType;
import java.util.Arrays;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

/**
 * Tracer proving GAP-06 end to end: a PUT to the subtask update route runs through the controller,
 * DTO validation, the ownership chain, the service's explicit version-compare-then-409-then-flush
 * guard, and back out through {@link com.vrudenko.kanban_board.handler.GlobalExceptionHandler}.
 * Modeled on {@code e2e.task.TaskLockingE2ETest} and {@code e2e.column.ColumnLockingE2ETest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class SubtaskLockingE2ETest extends AbstractAppE2ETest {

    @Autowired private ColumnService columnService;

    @Autowired private TaskService taskService;

    private String getSubtaskUrl(String boardId, String columnId, String taskId, String subtaskId) {
        return ApiPaths.BOARDS
                + "/"
                + boardId
                + ApiPaths.COLUMNS
                + "/"
                + columnId
                + ApiPaths.TASKS
                + "/"
                + taskId
                + ApiPaths.SUBTASKS
                + "/"
                + subtaskId;
    }

    /**
     * Creates a board/column/task/subtask owned by an arbitrary user, for the cross-user rejection
     * case. There is no REST endpoint for creating a board directly, so this goes through the
     * service layer directly, same as {@link AbstractAppTest#createColumnForUser}.
     */
    private SubtaskResponseDTO createSubtaskForUser(String userId) {
        var column =
                createColumnForUser(
                        userId,
                        dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4),
                        dataFactory.getRandomWord(ValidationConstants.MIN_COLUMN_NAME_LENGTH));

        var task =
                columnService.addTaskByColumnId(
                        userId,
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

        return taskService.addSubtaskByTaskId(
                userId,
                task.getId(),
                SaveSubtaskRequestDTO.builder()
                        .title(
                                dataFactory.getRandomText(
                                        ValidationConstants.MIN_SUBTASK_TITLE_LENGTH + 1))
                        .build());
    }

    @Nested
    class UpdateById {
        @Test
        void shouldReturnOkWithIncrementedVersion_whenVersionIsCurrent() {
            // arrange
            Pair<String, String> cookie = signin();
            var subtask = mockSubtasks.getFirst();
            var url =
                    getSubtaskUrl(
                            mockPopulatedBoard.getId(),
                            mockPopulatedColumn.getId(),
                            mockPopulatedTask.getId(),
                            subtask.getId());
            var startingVersion = subtask.getVersion();

            var updateDto =
                    UpdateSubtaskRequestDTO.builder()
                            .title("Updated with current version")
                            .version(startingVersion)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(updateDto)
                            .when()
                            .put(url)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var responseBody = response.as(SubtaskResponseDTO.class);
            Assertions.assertThat(responseBody.getVersion()).isEqualTo(startingVersion + 1);
        }

        @Test
        void shouldReturnConflictAndLeaveStateUnchanged_whenVersionIsStale() {
            // arrange
            Pair<String, String> cookie = signin();
            var subtask = mockSubtasks.get(1);
            var url =
                    getSubtaskUrl(
                            mockPopulatedBoard.getId(),
                            mockPopulatedColumn.getId(),
                            mockPopulatedTask.getId(),
                            subtask.getId());
            var startingVersion = subtask.getVersion();

            var firstUpdate =
                    UpdateSubtaskRequestDTO.builder()
                            .title("First writer wins")
                            .version(startingVersion)
                            .build();
            var staleUpdate =
                    UpdateSubtaskRequestDTO.builder()
                            .isCompleted(true)
                            .version(startingVersion)
                            .build();

            // act: first PUT with the starting version succeeds and bumps the version
            var firstResponse =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(firstUpdate)
                            .when()
                            .put(url)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(firstResponse.statusCode()).isEqualTo(HttpStatus.OK.value());

            // act: second PUT still holding the stale starting version is rejected
            var staleResponse =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(staleUpdate)
                            .when()
                            .put(url)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(staleResponse.statusCode())
                    .isEqualTo(HttpStatus.CONFLICT.value());

            // assert: a subsequent GET shows the original title/isCompleted unchanged
            var currentSubtasks =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(
                                    ApiPaths.BOARDS
                                            + "/"
                                            + mockPopulatedBoard.getId()
                                            + ApiPaths.COLUMNS
                                            + "/"
                                            + mockPopulatedColumn.getId()
                                            + ApiPaths.TASKS
                                            + "/"
                                            + mockPopulatedTask.getId()
                                            + ApiPaths.SUBTASKS)
                            .then()
                            .extract()
                            .as(SubtaskResponseDTO[].class);

            var matchingSubtasks =
                    Arrays.stream(currentSubtasks)
                            .filter(s -> s.getId().equals(subtask.getId()))
                            .toList();
            Assertions.assertThat(matchingSubtasks).hasSize(1);
            var reloaded = matchingSubtasks.getFirst();

            Assertions.assertThat(reloaded.getTitle()).isEqualTo("First writer wins");
            Assertions.assertThat(reloaded.getIsCompleted()).isFalse();
        }

        @Test
        void shouldReturnBadRequest_whenVersionIsMissing() {
            // arrange
            Pair<String, String> cookie = signin();
            var subtask = mockSubtasks.get(2);
            var url =
                    getSubtaskUrl(
                            mockPopulatedBoard.getId(),
                            mockPopulatedColumn.getId(),
                            mockPopulatedTask.getId(),
                            subtask.getId());

            var updateDtoWithoutVersion =
                    UpdateSubtaskRequestDTO.builder().title("No version here").build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(updateDtoWithoutVersion)
                            .when()
                            .put(url)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnUnauthorized_whenSubtaskOwnedByAnotherUser_andVersionCheckNeverRuns() {
            // arrange
            Pair<String, String> cookie = signin();
            var otherUser = createUser();
            var otherSubtask = createSubtaskForUser(otherUser.getId());

            // the board/column/task segments below only route the request — SubtaskController's
            // updateById method never binds them to a parameter, so the signed-in user's own ids
            // are fine here; only subtaskId (otherSubtask's) determines which entity is loaded.
            var url =
                    getSubtaskUrl(
                            mockPopulatedBoard.getId(),
                            mockPopulatedColumn.getId(),
                            mockPopulatedTask.getId(),
                            otherSubtask.getId());

            // an intentionally wrong version — if ownership were checked after the version
            // compare, this would surface as a 409 instead of a 401
            var updateDto =
                    UpdateSubtaskRequestDTO.builder()
                            .title("Attempted cross-user update")
                            .version(otherSubtask.getVersion() + 99)
                            .build();

            // act: attempt the update as the ORIGINAL signed-in user against another user's
            // subtask
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(updateDto)
                            .when()
                            .put(url)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
    }
}

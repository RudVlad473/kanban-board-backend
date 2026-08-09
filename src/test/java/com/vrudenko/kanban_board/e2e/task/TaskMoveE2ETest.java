package com.vrudenko.kanban_board.e2e.task;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;
import com.vrudenko.kanban_board.support.listeners.RecordingActivityEventListener;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskMoveE2ETest extends AbstractAppE2ETest {

    @Autowired private RecordingActivityEventListener recorder;

    private String getMoveUrl(String taskId) {
        return ApiPaths.TASKS + "/" + taskId + ApiPaths.MOVE;
    }

    private String getColumnTasksUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId + ApiPaths.TASKS;
    }

    private String getBoardColumnsUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS;
    }

    private ColumnResponseDTO createColumnOnBoard(Pair<String, String> cookie, String boardId) {
        return given().cookie(cookie.getFirst(), cookie.getSecond())
                .contentType(ContentType.JSON)
                .body(
                        SaveColumnRequestDTO.builder()
                                .name(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                .build())
                .when()
                .post(getBoardColumnsUrl(boardId))
                .then()
                .extract()
                .as(ColumnResponseDTO.class);
    }

    private List<TaskMovedEvent> recordedMovedEvents() {
        return recorder.getRecorded().stream()
                .filter(TaskMovedEvent.class::isInstance)
                .map(TaskMovedEvent.class::cast)
                .toList();
    }

    @Test
    void move_toColumnOnSameBoard_succeedsAndAnnouncesTaskMovedEvent() {
        // arrange
        recorder.clear();
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var sourceColumnId = mockPopulatedColumn.getId();
        var targetColumnId = mockColumns.getFirst().getId();
        var taskId = mockPopulatedTask.getId();
        var startingVersion = mockPopulatedTask.getVersion();

        var moveDto =
                MoveTaskRequestDTO.builder()
                        .targetColumnId(targetColumnId)
                        .version(startingVersion)
                        .build();

        // act
        var response =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .contentType(ContentType.JSON)
                        .body(moveDto)
                        .when()
                        .patch(getMoveUrl(taskId))
                        .then()
                        .extract();

        // assert
        Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        var responseBody = response.as(TaskResponseDTO.class);
        Assertions.assertThat(responseBody.getVersion()).isGreaterThan(startingVersion);

        // act
        var targetColumnTasks =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .when()
                        .get(getColumnTasksUrl(boardId, targetColumnId))
                        .then()
                        .extract()
                        .as(TaskResponseDTO[].class);
        var sourceColumnTasks =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .when()
                        .get(getColumnTasksUrl(boardId, sourceColumnId))
                        .then()
                        .extract()
                        .as(TaskResponseDTO[].class);

        // assert
        Assertions.assertThat(targetColumnTasks)
                .extracting(TaskResponseDTO::getId)
                .contains(taskId);
        Assertions.assertThat(sourceColumnTasks)
                .extracting(TaskResponseDTO::getId)
                .doesNotContain(taskId);

        // assert — exactly one TaskMovedEvent, server-derived fields, non-null id/timestamp
        var movedEvents = recordedMovedEvents();

        Assertions.assertThat(movedEvents).hasSize(1);
        var event = movedEvents.getFirst();
        Assertions.assertThat(event.taskId()).isEqualTo(taskId);
        Assertions.assertThat(event.sourceColumnId()).isEqualTo(sourceColumnId);
        Assertions.assertThat(event.targetColumnId()).isEqualTo(targetColumnId);
        Assertions.assertThat(event.boardId()).isEqualTo(boardId);
        Assertions.assertThat(event.userId()).isEqualTo(getOwningUser().getId());
        Assertions.assertThat(event.eventId()).isNotNull();
        Assertions.assertThat(event.timestamp()).isNotNull();
    }

    @Nested
    class MoveToColumn {

        @Nested
        class StaleVersion {
            @Test
            void shouldReturnConflict_whenVersionIsStale_andStaysRejectedOnRetry() {
                // arrange
                var cookie = signin();
                var boardId = mockPopulatedBoard.getId();
                var taskId = mockPopulatedTask.getId();
                var startingVersion = mockPopulatedTask.getVersion();
                var firstTargetColumnId = mockColumns.get(0).getId();
                var secondTargetColumnId = mockColumns.get(1).getId();

                var firstMove =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(firstTargetColumnId)
                                .version(startingVersion)
                                .build();
                var staleMove =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(secondTargetColumnId)
                                .version(startingVersion)
                                .build();

                // act: first move succeeds and bumps the version
                var firstResponse =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(firstMove)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(firstResponse.statusCode()).isEqualTo(HttpStatus.OK.value());

                recorder.clear();

                // act: second move still holding the pre-move version is rejected
                var secondResponse =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(staleMove)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(secondResponse.statusCode())
                        .isEqualTo(HttpStatus.CONFLICT.value());

                // act: retrying the same stale move again, without refetching, must still be
                // rejected
                var retryResponse =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(staleMove)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(retryResponse.statusCode())
                        .isEqualTo(HttpStatus.CONFLICT.value());

                // assert: the task's column is still the first move's target — rejected attempts
                // changed nothing
                var firstTargetTasks =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .when()
                                .get(getColumnTasksUrl(boardId, firstTargetColumnId))
                                .then()
                                .extract()
                                .as(TaskResponseDTO[].class);
                var secondTargetTasks =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .when()
                                .get(getColumnTasksUrl(boardId, secondTargetColumnId))
                                .then()
                                .extract()
                                .as(TaskResponseDTO[].class);

                Assertions.assertThat(firstTargetTasks)
                        .extracting(TaskResponseDTO::getId)
                        .contains(taskId);
                Assertions.assertThat(secondTargetTasks)
                        .extracting(TaskResponseDTO::getId)
                        .doesNotContain(taskId);

                // assert: no TaskMovedEvent for either rejected attempt
                Assertions.assertThat(recordedMovedEvents()).isEmpty();
            }
        }

        @Nested
        class ConcurrentConflict {
            @Test
            void shouldAcceptFirstWriter_andRejectSecondWriter_whenBothStartFromSameVersion() {
                // arrange
                recorder.clear();
                var cookie = signin();
                var boardId = mockPopulatedBoard.getId();
                var taskId = mockPopulatedTask.getId();
                var startingVersion = mockPopulatedTask.getVersion();
                var firstTargetColumnId = mockColumns.get(0).getId();
                var secondTargetColumnId = mockColumns.get(1).getId();

                var firstMove =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(firstTargetColumnId)
                                .version(startingVersion)
                                .build();
                var secondMove =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(secondTargetColumnId)
                                .version(startingVersion)
                                .build();

                // act
                var firstResponse =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(firstMove)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();
                var secondResponse =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(secondMove)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert: first wins, second is rejected — never a silent overwrite
                Assertions.assertThat(firstResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
                Assertions.assertThat(secondResponse.statusCode())
                        .isEqualTo(HttpStatus.CONFLICT.value());

                var firstTargetTasks =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .when()
                                .get(getColumnTasksUrl(boardId, firstTargetColumnId))
                                .then()
                                .extract()
                                .as(TaskResponseDTO[].class);
                var secondTargetTasks =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .when()
                                .get(getColumnTasksUrl(boardId, secondTargetColumnId))
                                .then()
                                .extract()
                                .as(TaskResponseDTO[].class);

                Assertions.assertThat(firstTargetTasks)
                        .extracting(TaskResponseDTO::getId)
                        .contains(taskId);
                Assertions.assertThat(secondTargetTasks)
                        .extracting(TaskResponseDTO::getId)
                        .doesNotContain(taskId);

                // assert: exactly one TaskMovedEvent — the accepted first writer's, none from the
                // rejected second
                Assertions.assertThat(recordedMovedEvents()).hasSize(1);
                Assertions.assertThat(recordedMovedEvents().getFirst().targetColumnId())
                        .isEqualTo(firstTargetColumnId);
            }
        }

        @Nested
        class CrossBoardTarget {
            @Test
            void shouldReturnBadRequest_whenTargetColumnIsOnDifferentBoardOwnedBySameUser() {
                // arrange
                recorder.clear();
                var cookie = signin();
                var boardId = mockPopulatedBoard.getId();
                var sourceColumnId = mockPopulatedColumn.getId();
                var taskId = mockPopulatedTask.getId();
                var startingVersion = mockPopulatedTask.getVersion();
                var otherBoardId = mockEmptyBoards.get(0).getId();
                var otherBoardColumn = createColumnOnBoard(cookie, otherBoardId);

                var moveDto =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(otherBoardColumn.getId())
                                .version(startingVersion)
                                .build();

                // act
                var response =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(moveDto)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(response.statusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST.value());

                var sourceColumnTasks =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .when()
                                .get(getColumnTasksUrl(boardId, sourceColumnId))
                                .then()
                                .extract()
                                .as(TaskResponseDTO[].class);

                Assertions.assertThat(sourceColumnTasks)
                        .extracting(TaskResponseDTO::getId)
                        .contains(taskId);
                Assertions.assertThat(recordedMovedEvents()).isEmpty();
            }
        }

        @Nested
        class UnownedTarget {
            @Test
            void shouldReturnUnauthorized_whenTargetColumnOwnedByAnotherUser() {
                // arrange
                recorder.clear();
                var cookie = signin();
                var boardId = mockPopulatedBoard.getId();
                var sourceColumnId = mockPopulatedColumn.getId();
                var taskId = mockPopulatedTask.getId();
                var startingVersion = mockPopulatedTask.getVersion();

                var otherUser = createUser();
                var otherColumn =
                        createColumnForUser(
                                otherUser.getId(),
                                dataFactory.getRandomWord(
                                        ValidationConstants.MIN_BOARD_NAME_LENGTH + 4),
                                dataFactory.getRandomWord(
                                        ValidationConstants.MIN_COLUMN_NAME_LENGTH));

                var moveDto =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(otherColumn.getId())
                                .version(startingVersion)
                                .build();

                // act: attempt the move as the ORIGINAL signed-in user
                var response =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(moveDto)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(response.statusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED.value());

                var sourceColumnTasks =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .when()
                                .get(getColumnTasksUrl(boardId, sourceColumnId))
                                .then()
                                .extract()
                                .as(TaskResponseDTO[].class);

                Assertions.assertThat(sourceColumnTasks)
                        .extracting(TaskResponseDTO::getId)
                        .contains(taskId);
                Assertions.assertThat(recordedMovedEvents()).isEmpty();
            }
        }

        @Nested
        class MissingVersion {
            @Test
            void shouldReturnBadRequest_whenVersionIsMissing() {
                // arrange
                var cookie = signin();
                var taskId = mockPopulatedTask.getId();
                var targetColumnId = mockColumns.getFirst().getId();

                var moveDtoWithoutVersion =
                        MoveTaskRequestDTO.builder().targetColumnId(targetColumnId).build();

                // act
                var response =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(moveDtoWithoutVersion)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(response.statusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST.value());
            }
        }

        @Nested
        class UnknownIds {
            @Test
            void shouldReturnNotFound_whenTaskIdIsUnknown() {
                // arrange
                var cookie = signin();
                var unknownTaskId = UUID.randomUUID().toString();
                var targetColumnId = mockColumns.getFirst().getId();

                var moveDto =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(targetColumnId)
                                .version(1L)
                                .build();

                // act
                var response =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(moveDto)
                                .when()
                                .patch(getMoveUrl(unknownTaskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(response.statusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND.value());
            }

            @Test
            void shouldReturnNotFound_whenTargetColumnIdIsUnknown() {
                // arrange
                var cookie = signin();
                var taskId = mockPopulatedTask.getId();
                var startingVersion = mockPopulatedTask.getVersion();
                var unknownColumnId = UUID.randomUUID().toString();

                var moveDto =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(unknownColumnId)
                                .version(startingVersion)
                                .build();

                // act
                var response =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .contentType(ContentType.JSON)
                                .body(moveDto)
                                .when()
                                .patch(getMoveUrl(taskId))
                                .then()
                                .extract();

                // assert
                Assertions.assertThat(response.statusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND.value());
            }
        }
    }
}

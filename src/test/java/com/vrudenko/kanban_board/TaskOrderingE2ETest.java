package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.repository.TaskRepository;
import io.restassured.http.ContentType;
import java.util.Comparator;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

/**
 * Plan 04's tracer (GAP-03): proves task creation assigns contiguous positions and the move
 * endpoint — extended with {@code targetPosition} per D-04, still the single existing move endpoint
 * — places a task at a chosen position, over real HTTP through the service to two coordinated
 * bulk-shift SQL statements and back, before column ordering or response-DTO {@code position}
 * exposure land in this plan's later tasks. Positions are asserted through {@link TaskRepository}
 * directly (sorted by the same {@code (position, id)} total order task 3 later bakes into the read
 * path), since {@code TaskResponseDTO} does not carry {@code position} until task 3.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskOrderingE2ETest extends AbstractAppE2ETest {

    @Autowired private TaskRepository taskRepository;

    // POST endpoint (add task to column): ColumnController's mapping, no /tasks suffix.
    private String getColumnTasksUrl(String columnId) {
        return ApiPaths.BOARDS
                + "/"
                + mockPopulatedBoard.getId()
                + ApiPaths.COLUMNS
                + "/"
                + columnId;
    }

    // GET endpoint (list tasks in column): TaskController's own, differently-nested mapping.
    private String getListTasksUrl(String columnId) {
        return getColumnTasksUrl(columnId) + ApiPaths.TASKS;
    }

    private String getBoardColumnsUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS;
    }

    private String getMoveUrl(String taskId) {
        return ApiPaths.TASKS + "/" + taskId + ApiPaths.MOVE;
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

    private ColumnResponseDTO createEmptyColumn(Pair<String, String> cookie) {
        return createColumnOnBoard(cookie, mockPopulatedBoard.getId());
    }

    private TaskResponseDTO createTaskInColumn(Pair<String, String> cookie, String columnId) {
        return given().cookie(cookie.getFirst(), cookie.getSecond())
                .contentType(ContentType.JSON)
                .body(
                        SaveTaskRequestDTO.builder()
                                .title(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                                .description(
                                        dataFactory.getRandomText(
                                                ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                                ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH))
                                .build())
                .when()
                .post(getColumnTasksUrl(columnId))
                .then()
                .extract()
                .as(TaskResponseDTO.class);
    }

    /**
     * Tasks of {@code columnId}, sorted by the same {@code (position, id)} total order task 3 later
     * applies to the real read path.
     */
    private List<TaskEntity> orderedTasks(String columnId) {
        return taskRepository.findAllByColumnId(columnId).stream()
                .sorted(
                        Comparator.comparing(TaskEntity::getPosition)
                                .thenComparing(TaskEntity::getId))
                .toList();
    }

    private List<String> orderedTaskIds(String columnId) {
        return orderedTasks(columnId).stream().map(TaskEntity::getId).toList();
    }

    private List<Integer> positionsOf(String columnId) {
        return orderedTasks(columnId).stream().map(TaskEntity::getPosition).toList();
    }

    @Nested
    class TaskCreation {
        @Test
        void shouldAssignContiguousPositions_whenCreatingThreeTasksInEmptyColumn() {
            // arrange
            var cookie = signin();
            var column = createEmptyColumn(cookie);

            // act
            var first = createTaskInColumn(cookie, column.getId());
            var second = createTaskInColumn(cookie, column.getId());
            var third = createTaskInColumn(cookie, column.getId());

            // assert
            Assertions.assertThat(orderedTaskIds(column.getId()))
                    .containsExactly(first.getId(), second.getId(), third.getId());
            Assertions.assertThat(positionsOf(column.getId())).containsExactly(0, 1, 2);
        }
    }

    @Nested
    class MoveToColumn {

        @Test
        void shouldMoveThirdTaskToFront_andShiftOthersDown_whenTargetPositionIsZeroInSameColumn() {
            // arrange
            var cookie = signin();
            var column = createEmptyColumn(cookie);
            var first = createTaskInColumn(cookie, column.getId());
            var second = createTaskInColumn(cookie, column.getId());
            var third = createTaskInColumn(cookie, column.getId());

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(column.getId())
                            .version(third.getVersion())
                            .targetPosition(0)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(moveDto)
                            .when()
                            .patch(getMoveUrl(third.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(column.getId()))
                    .containsExactly(third.getId(), first.getId(), second.getId());
            Assertions.assertThat(positionsOf(column.getId())).containsExactly(0, 1, 2);
        }

        @Test
        void shouldLeaveBothColumnsContiguous_whenMovingTaskToDifferentColumnAtPositionZero() {
            // arrange
            var cookie = signin();
            var sourceColumn = createEmptyColumn(cookie);
            var destColumn = createEmptyColumn(cookie);
            var sourceFirst = createTaskInColumn(cookie, sourceColumn.getId());
            var sourceSecond = createTaskInColumn(cookie, sourceColumn.getId());
            var destFirst = createTaskInColumn(cookie, destColumn.getId());

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(destColumn.getId())
                            .version(sourceFirst.getVersion())
                            .targetPosition(0)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(moveDto)
                            .when()
                            .patch(getMoveUrl(sourceFirst.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(sourceColumn.getId()))
                    .containsExactly(sourceSecond.getId());
            Assertions.assertThat(positionsOf(sourceColumn.getId())).containsExactly(0);
            Assertions.assertThat(orderedTaskIds(destColumn.getId()))
                    .containsExactly(sourceFirst.getId(), destFirst.getId());
            Assertions.assertThat(positionsOf(destColumn.getId())).containsExactly(0, 1);
        }

        @Test
        void shouldAppendAtEnd_whenTargetPositionIsOmitted() {
            // arrange
            var cookie = signin();
            var sourceColumn = createEmptyColumn(cookie);
            var destColumn = createEmptyColumn(cookie);
            var moving = createTaskInColumn(cookie, sourceColumn.getId());
            var destFirst = createTaskInColumn(cookie, destColumn.getId());
            var destSecond = createTaskInColumn(cookie, destColumn.getId());

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(destColumn.getId())
                            .version(moving.getVersion())
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(moveDto)
                            .when()
                            .patch(getMoveUrl(moving.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(destColumn.getId()))
                    .containsExactly(destFirst.getId(), destSecond.getId(), moving.getId());
        }

        @Test
        void shouldClampToEnd_whenTargetPositionExceedsDestinationSize() {
            // arrange
            var cookie = signin();
            var sourceColumn = createEmptyColumn(cookie);
            var destColumn = createEmptyColumn(cookie);
            var moving = createTaskInColumn(cookie, sourceColumn.getId());
            var destFirst = createTaskInColumn(cookie, destColumn.getId());
            var destSecond = createTaskInColumn(cookie, destColumn.getId());

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(destColumn.getId())
                            .version(moving.getVersion())
                            .targetPosition(999)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(moveDto)
                            .when()
                            .patch(getMoveUrl(moving.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(destColumn.getId()))
                    .containsExactly(destFirst.getId(), destSecond.getId(), moving.getId());
            Assertions.assertThat(positionsOf(destColumn.getId())).containsExactly(0, 1, 2);
        }

        @Test
        void shouldReturnBadRequest_whenTargetPositionIsNegative() {
            // arrange
            var cookie = signin();
            var column = createEmptyColumn(cookie);
            var task = createTaskInColumn(cookie, column.getId());

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(column.getId())
                            .version(task.getVersion())
                            .targetPosition(-1)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(moveDto)
                            .when()
                            .patch(getMoveUrl(task.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale() {
            // arrange
            var cookie = signin();
            var column = createEmptyColumn(cookie);
            var first = createTaskInColumn(cookie, column.getId());
            var second = createTaskInColumn(cookie, column.getId());

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(column.getId())
                            .version(first.getVersion() + 99)
                            .targetPosition(1)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(moveDto)
                            .when()
                            .patch(getMoveUrl(first.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            Assertions.assertThat(orderedTaskIds(column.getId()))
                    .containsExactly(first.getId(), second.getId());
            Assertions.assertThat(positionsOf(column.getId())).containsExactly(0, 1);
        }

        @Test
        void shouldReturnBadRequest_whenTargetColumnIsOnDifferentBoard_beforePositionWorkRuns() {
            // arrange
            var cookie = signin();
            var otherBoardId = mockEmptyBoards.get(0).getId();
            var otherBoardColumn = createColumnOnBoard(cookie, otherBoardId);
            var task = mockPopulatedTask;

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(otherBoardColumn.getId())
                            .version(task.getVersion())
                            .targetPosition(0)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(moveDto)
                            .when()
                            .patch(getMoveUrl(task.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnSameOrderTwice_whenReadingSameColumnRepeatedly() {
            // arrange
            var cookie = signin();
            var column = createEmptyColumn(cookie);
            createTaskInColumn(cookie, column.getId());
            createTaskInColumn(cookie, column.getId());
            createTaskInColumn(cookie, column.getId());

            // act — reads TaskRepository.findAllByColumnId directly, with no additional sort
            // applied by this test: the (position, id) order must come from the production query
            // itself, not from a test-side re-sort, or a future change that drops the id tiebreak
            // would pass here undetected.
            var firstRead =
                    taskRepository.findAllByColumnId(column.getId()).stream()
                            .map(TaskEntity::getId)
                            .toList();
            var secondRead =
                    taskRepository.findAllByColumnId(column.getId()).stream()
                            .map(TaskEntity::getId)
                            .toList();

            // assert
            Assertions.assertThat(firstRead).isEqualTo(secondRead);
        }

        @Test
        void shouldReturnTasksSortedByPosition_overHttp() {
            // arrange
            var cookie = signin();
            var column = createEmptyColumn(cookie);
            var first = createTaskInColumn(cookie, column.getId());
            var second = createTaskInColumn(cookie, column.getId());
            var third = createTaskInColumn(cookie, column.getId());

            var moveDto =
                    MoveTaskRequestDTO.builder()
                            .targetColumnId(column.getId())
                            .version(third.getVersion())
                            .targetPosition(0)
                            .build();
            given().cookie(cookie.getFirst(), cookie.getSecond())
                    .contentType(ContentType.JSON)
                    .body(moveDto)
                    .when()
                    .patch(getMoveUrl(third.getId()))
                    .then()
                    .extract();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(getListTasksUrl(column.getId()))
                            .then()
                            .extract()
                            .as(TaskResponseDTO[].class);

            // assert
            Assertions.assertThat(response)
                    .extracting(TaskResponseDTO::getId)
                    .containsExactly(third.getId(), first.getId(), second.getId());
            Assertions.assertThat(response)
                    .extracting(TaskResponseDTO::getPosition)
                    .containsExactly(0, 1, 2);
        }
    }
}

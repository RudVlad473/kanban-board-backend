package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.ReorderColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.repository.ColumnRepository;
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
 * Plan 04's board-scoped mirror of {@code TaskOrderingE2ETest}: proves column creation assigns
 * contiguous positions, the new reorder route places a column at a chosen position under an
 * optimistic-lock guard, and deleting a column (plan 03's route) closes the gap it leaves.
 * Positions are asserted through {@link ColumnRepository} directly, since {@code ColumnResponseDTO}
 * does not carry {@code position} until task 3 of this plan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ColumnOrderingE2ETest extends AbstractAppE2ETest {

    @Autowired private ColumnRepository columnRepository;

    @Autowired private TaskRepository taskRepository;

    private String getBoardColumnsUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS;
    }

    private String getReorderUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS
                + "/"
                + boardId
                + ApiPaths.COLUMNS
                + "/"
                + columnId
                + ApiPaths.REORDER;
    }

    private String getColumnTasksUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId;
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

    private TaskResponseDTO createTaskInColumn(
            Pair<String, String> cookie, String boardId, String columnId) {
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
                .post(getColumnTasksUrl(boardId, columnId))
                .then()
                .extract()
                .as(TaskResponseDTO.class);
    }

    private List<ColumnEntity> orderedColumns(String boardId) {
        return columnRepository.findAllByBoardId(boardId).stream()
                .sorted(
                        Comparator.comparing(ColumnEntity::getPosition)
                                .thenComparing(ColumnEntity::getId))
                .toList();
    }

    private List<String> orderedColumnIds(String boardId) {
        return orderedColumns(boardId).stream().map(ColumnEntity::getId).toList();
    }

    private List<Integer> positionsOf(String boardId) {
        return orderedColumns(boardId).stream().map(ColumnEntity::getPosition).toList();
    }

    @Nested
    class ColumnCreation {
        @Test
        void shouldAssignContiguousPositions_whenCreatingThreeColumnsOnOneBoard() {
            // arrange
            var cookie = signin();
            var boardId = mockEmptyBoards.get(0).getId();

            // act
            var first = createColumnOnBoard(cookie, boardId);
            var second = createColumnOnBoard(cookie, boardId);
            var third = createColumnOnBoard(cookie, boardId);

            // assert
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(first.getId(), second.getId(), third.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1, 2);
        }
    }

    @Nested
    class Reorder {

        @Test
        void shouldMoveThirdColumnToFront_andShiftOthersDown_whenTargetPositionIsZero() {
            // arrange
            var cookie = signin();
            var boardId = mockEmptyBoards.get(0).getId();
            var first = createColumnOnBoard(cookie, boardId);
            var second = createColumnOnBoard(cookie, boardId);
            var third = createColumnOnBoard(cookie, boardId);

            var reorderDto =
                    ReorderColumnRequestDTO.builder()
                            .version(third.getVersion())
                            .targetPosition(0)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(reorderDto)
                            .when()
                            .patch(getReorderUrl(boardId, third.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(third.getId(), first.getId(), second.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1, 2);
        }

        @Test
        void shouldClampToEnd_whenTargetPositionExceedsBoardColumnCount() {
            // arrange
            var cookie = signin();
            var boardId = mockEmptyBoards.get(0).getId();
            var first = createColumnOnBoard(cookie, boardId);
            var second = createColumnOnBoard(cookie, boardId);
            var third = createColumnOnBoard(cookie, boardId);

            var reorderDto =
                    ReorderColumnRequestDTO.builder()
                            .version(first.getVersion())
                            .targetPosition(999)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(reorderDto)
                            .when()
                            .patch(getReorderUrl(boardId, first.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(second.getId(), third.getId(), first.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1, 2);
        }

        @Test
        void shouldReturnBadRequest_whenTargetPositionIsNegative() {
            // arrange
            var cookie = signin();
            var boardId = mockEmptyBoards.get(0).getId();
            var column = createColumnOnBoard(cookie, boardId);

            var reorderDto =
                    ReorderColumnRequestDTO.builder()
                            .version(column.getVersion())
                            .targetPosition(-1)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(reorderDto)
                            .when()
                            .patch(getReorderUrl(boardId, column.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale() {
            // arrange
            var cookie = signin();
            var boardId = mockEmptyBoards.get(0).getId();
            var first = createColumnOnBoard(cookie, boardId);
            var second = createColumnOnBoard(cookie, boardId);

            var reorderDto =
                    ReorderColumnRequestDTO.builder()
                            .version(first.getVersion() + 99)
                            .targetPosition(1)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(reorderDto)
                            .when()
                            .patch(getReorderUrl(boardId, first.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(first.getId(), second.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1);
        }

        @Test
        void shouldReturnUnauthorized_whenColumnBelongsToAnotherUsersBoard() {
            // arrange
            var cookie = signin();
            var otherUser = createUser();
            var otherColumn =
                    createColumnForUser(
                            otherUser.getId(),
                            dataFactory.getRandomWord(
                                    ValidationConstants.MIN_BOARD_NAME_LENGTH + 4),
                            dataFactory.getRandomWord(ValidationConstants.MIN_COLUMN_NAME_LENGTH));

            var reorderDto =
                    ReorderColumnRequestDTO.builder()
                            .version(otherColumn.getVersion())
                            .targetPosition(0)
                            .build();

            // act: attempt the reorder as the ORIGINAL signed-in user against another user's
            // column
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(reorderDto)
                            .when()
                            .patch(getReorderUrl(mockPopulatedBoard.getId(), otherColumn.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        void shouldNotChangeAnyTaskPosition_whenReorderingAColumn() {
            // arrange
            var cookie = signin();
            var boardId = mockEmptyBoards.get(0).getId();
            var first = createColumnOnBoard(cookie, boardId);
            var second = createColumnOnBoard(cookie, boardId);
            var firstTaskA = createTaskInColumn(cookie, boardId, first.getId());
            var firstTaskB = createTaskInColumn(cookie, boardId, first.getId());
            var secondTaskA = createTaskInColumn(cookie, boardId, second.getId());

            var positionsBefore =
                    List.of(
                            taskRepository.findById(firstTaskA.getId()).orElseThrow().getPosition(),
                            taskRepository.findById(firstTaskB.getId()).orElseThrow().getPosition(),
                            taskRepository
                                    .findById(secondTaskA.getId())
                                    .orElseThrow()
                                    .getPosition());

            var reorderDto =
                    ReorderColumnRequestDTO.builder()
                            .version(second.getVersion())
                            .targetPosition(0)
                            .build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(reorderDto)
                            .when()
                            .patch(getReorderUrl(boardId, second.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var positionsAfter =
                    List.of(
                            taskRepository.findById(firstTaskA.getId()).orElseThrow().getPosition(),
                            taskRepository.findById(firstTaskB.getId()).orElseThrow().getPosition(),
                            taskRepository
                                    .findById(secondTaskA.getId())
                                    .orElseThrow()
                                    .getPosition());
            Assertions.assertThat(positionsAfter).isEqualTo(positionsBefore);
        }
    }

    @Nested
    class DeleteById {
        @Test
        void shouldLeaveSurvivingColumnsContiguousFromZero_whenDeletingAMiddleColumn() {
            // arrange
            var cookie = signin();
            var boardId = mockEmptyBoards.get(0).getId();
            var first = createColumnOnBoard(cookie, boardId);
            var middle = createColumnOnBoard(cookie, boardId);
            var last = createColumnOnBoard(cookie, boardId);

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .delete(getColumnTasksUrl(boardId, middle.getId()));

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(first.getId(), last.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1);
        }
    }
}

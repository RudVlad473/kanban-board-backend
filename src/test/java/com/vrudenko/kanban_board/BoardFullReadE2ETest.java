package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.service.UserService;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

/**
 * Tracer proving GAP-04 end to end: one authenticated GET on {@code /boards/{boardId}/full} runs
 * through the controller, {@link com.vrudenko.kanban_board.service.BoardService#findFullById}'s
 * ownership-verified fetch-join query, and the composed {@link
 * com.vrudenko.kanban_board.mapper.BoardFullMapper} chain, returning board, columns, tasks and
 * subtasks four levels deep in a single nested document. Modeled on {@link
 * BoardCreationE2ETest}/{@link SubtaskLockingE2ETest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class BoardFullReadE2ETest extends AbstractAppE2ETest {

    @Autowired private UserService userService;

    @Autowired private BoardService boardService;

    private String getFullBoardUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.FULL;
    }

    @Nested
    class GetFullBoard {
        @Test
        void shouldReturnNestedDocumentFourLevelsDeep_whenBoardHasColumnsTasksAndSubtasks() {
            // arrange
            Pair<String, String> cookie = signin();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(getFullBoardUrl(mockPopulatedBoard.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var body = response.as(BoardFullResponseDTO.class);
            Assertions.assertThat(body.getId()).isEqualTo(mockPopulatedBoard.getId());
            Assertions.assertThat(body.getName()).isEqualTo(mockPopulatedBoard.getName());
            Assertions.assertThat(body.getColumns()).isNotEmpty();

            var column =
                    body.getColumns().stream()
                            .filter(c -> c.getId().equals(mockPopulatedColumn.getId()))
                            .findFirst()
                            .orElseThrow();
            Assertions.assertThat(column.getName()).isEqualTo(mockPopulatedColumn.getName());
            Assertions.assertThat(column.getVersion()).isNotNull();
            Assertions.assertThat(column.getPosition()).isNotNull();
            Assertions.assertThat(column.getTasks()).isNotEmpty();

            var task =
                    column.getTasks().stream()
                            .filter(t -> t.getId().equals(mockPopulatedTask.getId()))
                            .findFirst()
                            .orElseThrow();
            Assertions.assertThat(task.getTitle()).isEqualTo(mockPopulatedTask.getTitle());
            Assertions.assertThat(task.getDescription())
                    .isEqualTo(mockPopulatedTask.getDescription());
            Assertions.assertThat(task.getVersion()).isNotNull();
            Assertions.assertThat(task.getPosition()).isNotNull();
            Assertions.assertThat(task.getSubtasks()).isNotEmpty();

            var subtask = task.getSubtasks().getFirst();
            Assertions.assertThat(subtask.getId()).isNotBlank();
            Assertions.assertThat(subtask.getTitle()).isNotBlank();
            Assertions.assertThat(subtask.getVersion()).isNotNull();
        }

        @Test
        void shouldReturnEmptyColumnsArray_whenBoardHasNoColumns() {
            // arrange
            Pair<String, String> cookie = signin();
            var emptyBoard = mockEmptyBoards.getFirst();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(getFullBoardUrl(emptyBoard.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var body = response.as(BoardFullResponseDTO.class);
            Assertions.assertThat(body.getColumns()).isNotNull().isEmpty();
        }

        @Test
        void shouldReturnEmptyTasksArray_whenColumnHasNoTasks() {
            // arrange -- mockColumns are columns added directly to mockPopulatedBoard with no
            // tasks of their own (only mockPopulatedColumn, added separately, has tasks).
            Pair<String, String> cookie = signin();
            var taskLessColumn = mockColumns.getFirst();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(getFullBoardUrl(mockPopulatedBoard.getId()))
                            .then()
                            .extract();

            // assert
            var body = response.as(BoardFullResponseDTO.class);
            var reloadedColumn =
                    body.getColumns().stream()
                            .filter(c -> c.getId().equals(taskLessColumn.getId()))
                            .findFirst()
                            .orElseThrow();
            Assertions.assertThat(reloadedColumn.getTasks()).isNotNull().isEmpty();
        }

        @Test
        void shouldReturnEmptySubtasksArray_whenTaskHasNoSubtasks() {
            // arrange -- mockTasks are tasks added to mockPopulatedColumn with no subtasks of
            // their own (only mockPopulatedTask, added separately, has subtasks).
            Pair<String, String> cookie = signin();
            var subtaskLessTask = mockTasks.getFirst();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(getFullBoardUrl(mockPopulatedBoard.getId()))
                            .then()
                            .extract();

            // assert
            var body = response.as(BoardFullResponseDTO.class);
            var reloadedColumn =
                    body.getColumns().stream()
                            .filter(c -> c.getId().equals(mockPopulatedColumn.getId()))
                            .findFirst()
                            .orElseThrow();
            var reloadedTask =
                    reloadedColumn.getTasks().stream()
                            .filter(t -> t.getId().equals(subtaskLessTask.getId()))
                            .findFirst()
                            .orElseThrow();
            Assertions.assertThat(reloadedTask.getSubtasks()).isNotNull().isEmpty();
        }

        @Test
        void shouldReturnUnauthorizedAndDiscloseNothing_whenBoardOwnedByAnotherUser() {
            // arrange
            Pair<String, String> cookie = signin();
            var otherUser = createUser();
            var otherBoard =
                    userService.addBoardByUserId(
                            otherUser.getId(),
                            SaveBoardRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_BOARD_NAME_LENGTH + 4))
                                    .build());
            boardService.addColumnByBoardId(
                    otherUser.getId(),
                    otherBoard.getId(),
                    SaveColumnRequestDTO.builder()
                            .name(
                                    dataFactory.getRandomWord(
                                            ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                            .build());

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(getFullBoardUrl(otherBoard.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(response.asString()).doesNotContain(otherBoard.getName());
        }

        @Test
        void shouldReturnNotFound_whenBoardDoesNotExist() {
            // arrange
            Pair<String, String> cookie = signin();
            var unknownBoardId = UUID.randomUUID().toString();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(getFullBoardUrl(unknownBoardId))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
        }
    }
}

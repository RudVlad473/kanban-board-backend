package com.vrudenko.kanban_board.controller;

import java.util.Arrays;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.service.UserService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Tracer proving GAP-04 end to end: one authenticated GET on {@code /boards/{boardId}/full} runs
 * through the controller, {@link com.vrudenko.kanban_board.service.BoardService#findFullById}'s
 * ownership-verified fetch-join query, and the composed {@link
 * com.vrudenko.kanban_board.mapper.BoardFullMapper} chain, returning board, columns, tasks and
 * subtasks four levels deep in a single nested document. Modeled on {@link
 * com.vrudenko.kanban_board.e2e.board.BoardCreationE2ETest}/{@link
 * com.vrudenko.kanban_board.e2e.subtask.SubtaskLockingTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class BoardFullReadTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private UserService userService;

    @Autowired private BoardService boardService;

    private String getFullBoardUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.FULL;
    }

    private String getFlatColumnsUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS;
    }

    private String getFlatTasksUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId + ApiPaths.TASKS;
    }

    private String getFlatSubtasksUrl(String boardId, String columnId, String taskId) {
        return ApiPaths.BOARDS
                + "/"
                + boardId
                + ApiPaths.COLUMNS
                + "/"
                + columnId
                + ApiPaths.TASKS
                + "/"
                + taskId
                + ApiPaths.SUBTASKS;
    }

    @Nested
    class GetFullBoard {
        @Test
        void shouldReturnNestedDocumentFourLevelsDeep_whenBoardHasColumnsTasksAndSubtasks()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();

            // act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var body =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);
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
        void shouldReturnColorOnNestedColumn_matchingCreatedValue() throws Exception {
            // arrange -- a column created WITH a color, so the nested read has something
            // non-null to carry through the ColumnFullMapper chain
            var coloredColumn =
                    boardService.addColumnByBoardId(
                            getOwningUser().getId(),
                            mockPopulatedBoard.getId(),
                            SaveColumnRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                    .color("#AbCdEf")
                                    .build());
            Cookie cookie = signinCookie();

            // act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var body =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);
            var nestedColumn =
                    body.getColumns().stream()
                            .filter(c -> c.getId().equals(coloredColumn.getId()))
                            .findFirst()
                            .orElseThrow();
            Assertions.assertThat(nestedColumn.getColor()).isEqualTo("#AbCdEf");
        }

        @Test
        void shouldReturnBoardsOwnCreatedAt_matchingFlatEndpoint() throws Exception {
            // arrange
            Cookie cookie = signinCookie();

            // act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var body =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);
            Assertions.assertThat(body.getCreatedAt()).isNotNull();
            Assertions.assertThat(body.getCreatedAt()).isEqualTo(mockPopulatedBoard.getCreatedAt());
        }

        @Test
        void shouldReturnEmptyColumnsArray_whenBoardHasNoColumns() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var emptyBoard = mockEmptyBoards.getFirst();

            // act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(emptyBoard.getId())).cookie(cookie))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var body =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);
            Assertions.assertThat(body.getColumns()).isNotNull().isEmpty();
        }

        @Test
        void shouldReturnEmptyTasksArray_whenColumnHasNoTasks() throws Exception {
            // arrange -- mockColumns are columns added directly to mockPopulatedBoard with no
            // tasks of their own (only mockPopulatedColumn, added separately, has tasks).
            Cookie cookie = signinCookie();
            var taskLessColumn = mockColumns.getFirst();

            // act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();

            // assert
            var body =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);
            var reloadedColumn =
                    body.getColumns().stream()
                            .filter(c -> c.getId().equals(taskLessColumn.getId()))
                            .findFirst()
                            .orElseThrow();
            Assertions.assertThat(reloadedColumn.getTasks()).isNotNull().isEmpty();
        }

        @Test
        void shouldReturnEmptySubtasksArray_whenTaskHasNoSubtasks() throws Exception {
            // arrange -- mockTasks are tasks added to mockPopulatedColumn with no subtasks of
            // their own (only mockPopulatedTask, added separately, has subtasks).
            Cookie cookie = signinCookie();
            var subtaskLessTask = mockTasks.getFirst();

            // act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();

            // assert
            var body =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);
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
        void shouldReturnForbiddenAndDiscloseNothing_whenBoardOwnedByAnotherUser()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
                    mockMvc.perform(get(getFullBoardUrl(otherBoard.getId())).cookie(cookie))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(response.getResponse().getContentAsString())
                    .doesNotContain(otherBoard.getName());
        }

        @Test
        void shouldReturnNotFound_whenBoardDoesNotExist() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var unknownBoardId = UUID.randomUUID().toString();

            // act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(unknownBoardId)).cookie(cookie))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }
    }

    // Confirms the nested read is a genuine replacement for the four-round-trip fan-out, not a
    // lossy summary of it, and that the four flat endpoints are untouched by this plan.
    // Quick task 260825-h7m: also asserts equivalence at the board level itself (name, version,
    // createdAt), not only from the columns down -- the board level was the untested gap through
    // which createdAt reached production missing from this document.
    @Nested
    class FlatEquivalence {
        @Test
        void shouldMatchFlatEndpointsFieldByField_forSameBoard() throws Exception {
            // arrange
            Cookie cookie = signinCookie();

            // act
            var nestedResponse =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();
            var nestedBody =
                    objectMapper.readValue(
                            nestedResponse.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);

            var flatColumnsResponse =
                    mockMvc.perform(
                                    get(getFlatColumnsUrl(mockPopulatedBoard.getId()))
                                            .cookie(cookie))
                            .andReturn();
            var flatColumns =
                    objectMapper.readValue(
                            flatColumnsResponse.getResponse().getContentAsString(),
                            ColumnResponseDTO[].class);

            var flatBoardsResponse =
                    mockMvc.perform(get(ApiPaths.BOARDS).cookie(cookie)).andReturn();
            var flatBoards =
                    objectMapper.readValue(
                            flatBoardsResponse.getResponse().getContentAsString(),
                            BoardResponseDTO[].class);

            // assert -- the board's own fields (name, version, createdAt) are present and equal
            // on the nested document, not only its columns/tasks/subtasks
            var flatBoard =
                    Arrays.stream(flatBoards)
                            .filter(b -> b.getId().equals(mockPopulatedBoard.getId()))
                            .findFirst()
                            .orElseThrow();
            Assertions.assertThat(nestedBody.getName()).isEqualTo(flatBoard.getName());
            Assertions.assertThat(nestedBody.getVersion()).isEqualTo(flatBoard.getVersion());
            Assertions.assertThat(nestedBody.getCreatedAt()).isEqualTo(flatBoard.getCreatedAt());

            // assert -- every column field the flat DTO carries (id, name, version) is present
            // and equal on the corresponding nested object
            for (var flatColumn : flatColumns) {
                var nestedColumn =
                        nestedBody.getColumns().stream()
                                .filter(c -> c.getId().equals(flatColumn.getId()))
                                .findFirst()
                                .orElseThrow();

                Assertions.assertThat(nestedColumn.getName()).isEqualTo(flatColumn.getName());
                Assertions.assertThat(nestedColumn.getVersion()).isEqualTo(flatColumn.getVersion());

                var flatTasksResponse =
                        mockMvc.perform(
                                        get(getFlatTasksUrl(
                                                        mockPopulatedBoard.getId(),
                                                        flatColumn.getId()))
                                                .cookie(cookie))
                                .andReturn();
                var flatTasks =
                        objectMapper.readValue(
                                flatTasksResponse.getResponse().getContentAsString(),
                                TaskResponseDTO[].class);

                for (var flatTask : flatTasks) {
                    var nestedTask =
                            nestedColumn.getTasks().stream()
                                    .filter(t -> t.getId().equals(flatTask.getId()))
                                    .findFirst()
                                    .orElseThrow();

                    Assertions.assertThat(nestedTask.getTitle()).isEqualTo(flatTask.getTitle());
                    Assertions.assertThat(nestedTask.getDescription())
                            .isEqualTo(flatTask.getDescription());
                    Assertions.assertThat(nestedTask.getVersion()).isEqualTo(flatTask.getVersion());

                    var flatSubtasksResponse =
                            mockMvc.perform(
                                            get(getFlatSubtasksUrl(
                                                            mockPopulatedBoard.getId(),
                                                            flatColumn.getId(),
                                                            flatTask.getId()))
                                                    .cookie(cookie))
                                    .andReturn();
                    var flatSubtasks =
                            objectMapper.readValue(
                                    flatSubtasksResponse.getResponse().getContentAsString(),
                                    SubtaskResponseDTO[].class);

                    for (var flatSubtask : flatSubtasks) {
                        var nestedSubtask =
                                nestedTask.getSubtasks().stream()
                                        .filter(s -> s.getId().equals(flatSubtask.getId()))
                                        .findFirst()
                                        .orElseThrow();

                        Assertions.assertThat(nestedSubtask.getTitle())
                                .isEqualTo(flatSubtask.getTitle());
                        Assertions.assertThat(nestedSubtask.getIsCompleted())
                                .isEqualTo(flatSubtask.getIsCompleted());
                        Assertions.assertThat(nestedSubtask.getVersion())
                                .isEqualTo(flatSubtask.getVersion());
                    }
                }
            }
        }

        @Test
        void shouldContainSameElementsAsFlatEndpoints_andBeInternallyOrdered_forSameBoard()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();

            // act
            var nestedResponse =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();
            var nestedBody =
                    objectMapper.readValue(
                            nestedResponse.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);

            var flatColumnsResponse =
                    mockMvc.perform(
                                    get(getFlatColumnsUrl(mockPopulatedBoard.getId()))
                                            .cookie(cookie))
                            .andReturn();
            var flatColumns =
                    objectMapper.readValue(
                            flatColumnsResponse.getResponse().getContentAsString(),
                            ColumnResponseDTO[].class);

            var flatTasksResponse =
                    mockMvc.perform(
                                    get(getFlatTasksUrl(
                                                    mockPopulatedBoard.getId(),
                                                    mockPopulatedColumn.getId()))
                                            .cookie(cookie))
                            .andReturn();
            var flatTasks =
                    objectMapper.readValue(
                            flatTasksResponse.getResponse().getContentAsString(),
                            TaskResponseDTO[].class);

            // assert -- same elements as the flat endpoints, order-agnostic. A strict
            // element-for-element order match against the flat endpoints was tried first and
            // found genuinely flaky: neither ColumnRepository.findAllByBoardId nor
            // TaskRepository.findAllByColumnId carries an explicit ORDER BY (verified -- no
            // ordering feature has landed in this wave), so their row order is whatever
            // PostgreSQL's query planner happens to produce for that query shape on that run,
            // observed directly to vary run-to-run for the SAME data. GAP-04's nested query has a
            // structurally different (multi-level JOIN) shape, so there is no reliable way to make
            // its natural order coincide with the flat endpoints' incidental order without adding
            // an explicit ORDER BY to the flat repositories themselves -- out of this plan's scope
            // (ColumnRepository.java/TaskRepository.java belong to the sibling ordering plan
            // running in a separate worktree). What IS reliably true, and asserted below: the
            // nested response carries exactly the same elements as the flat endpoints (nothing
            // dropped or duplicated by the fetch-join/Set conversion), and the nested arrays have
            // their own well-defined, deterministic order (id-ascending, via @OrderBy("id") on the
            // entity collections) rather than HashSet's undefined iteration order.
            var nestedColumnIds = nestedBody.getColumns().stream().map(c -> c.getId()).toList();
            var flatColumnIds = Arrays.stream(flatColumns).map(ColumnResponseDTO::getId).toList();
            Assertions.assertThat(nestedColumnIds)
                    .containsExactlyInAnyOrderElementsOf(flatColumnIds);
            Assertions.assertThat(nestedColumnIds).isSorted();

            var nestedColumn =
                    nestedBody.getColumns().stream()
                            .filter(c -> c.getId().equals(mockPopulatedColumn.getId()))
                            .findFirst()
                            .orElseThrow();
            var nestedTaskIds = nestedColumn.getTasks().stream().map(t -> t.getId()).toList();
            var flatTaskIds = Arrays.stream(flatTasks).map(TaskResponseDTO::getId).toList();
            Assertions.assertThat(nestedTaskIds).containsExactlyInAnyOrderElementsOf(flatTaskIds);
            Assertions.assertThat(nestedTaskIds).isSorted();
        }
    }
}

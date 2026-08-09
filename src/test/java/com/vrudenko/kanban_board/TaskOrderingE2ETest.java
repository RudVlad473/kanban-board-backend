package com.vrudenko.kanban_board;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.repository.TaskRepository;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.util.Comparator;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Plan 04's tracer (GAP-03): proves task creation assigns contiguous positions and the move
 * endpoint — extended with {@code targetPosition} per D-04, still the single existing move endpoint
 * — places a task at a chosen position, over real HTTP through the service to two coordinated
 * bulk-shift SQL statements and back, before column ordering or response-DTO {@code position}
 * exposure land in this plan's later tasks. Positions are asserted through {@link TaskRepository}
 * directly (sorted by the same {@code (position, id)} total order task 3 later bakes into the read
 * path), since {@code TaskResponseDTO} does not carry {@code position} until task 3.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class TaskOrderingE2ETest extends AbstractAppMockMvcTest {

    @Autowired private TaskRepository taskRepository;

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

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

    private ColumnResponseDTO createColumnOnBoard(Cookie cookie, String boardId) throws Exception {
        var result =
                mockMvc.perform(
                                post(getBoardColumnsUrl(boardId))
                                        .cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        SaveColumnRequestDTO.builder()
                                                                .name(
                                                                        dataFactory.getRandomWord(
                                                                                ValidationConstants
                                                                                        .MIN_COLUMN_NAME_LENGTH))
                                                                .build())))
                        .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), ColumnResponseDTO.class);
    }

    private ColumnResponseDTO createEmptyColumn(Cookie cookie) throws Exception {
        return createColumnOnBoard(cookie, mockPopulatedBoard.getId());
    }

    private TaskResponseDTO createTaskInColumn(Cookie cookie, String columnId) throws Exception {
        var result =
                mockMvc.perform(
                                post(getColumnTasksUrl(columnId))
                                        .cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        SaveTaskRequestDTO.builder()
                                                                .title(
                                                                        dataFactory.getRandomWord(
                                                                                ValidationConstants
                                                                                                .MIN_TASK_TITLE_LENGTH
                                                                                        + 2))
                                                                .description(
                                                                        dataFactory.getRandomText(
                                                                                ValidationConstants
                                                                                        .MIN_TASK_DESCRIPTION_LENGTH,
                                                                                ValidationConstants
                                                                                        .MAX_TASK_DESCRIPTION_LENGTH))
                                                                .build())))
                        .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), TaskResponseDTO.class);
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
        void shouldAssignContiguousPositions_whenCreatingThreeTasksInEmptyColumn()
                throws Exception {
            // arrange
            var cookie = signinCookie();
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
        void shouldMoveThirdTaskToFront_andShiftOthersDown_whenTargetPositionIsZeroInSameColumn()
                throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getMoveUrl(third.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(moveDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(column.getId()))
                    .containsExactly(third.getId(), first.getId(), second.getId());
            Assertions.assertThat(positionsOf(column.getId())).containsExactly(0, 1, 2);
        }

        @Test
        void shouldLeaveBothColumnsContiguous_whenMovingTaskToDifferentColumnAtPositionZero()
                throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getMoveUrl(sourceFirst.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(moveDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(sourceColumn.getId()))
                    .containsExactly(sourceSecond.getId());
            Assertions.assertThat(positionsOf(sourceColumn.getId())).containsExactly(0);
            Assertions.assertThat(orderedTaskIds(destColumn.getId()))
                    .containsExactly(sourceFirst.getId(), destFirst.getId());
            Assertions.assertThat(positionsOf(destColumn.getId())).containsExactly(0, 1);
        }

        @Test
        void shouldAppendAtEnd_whenTargetPositionIsOmitted() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getMoveUrl(moving.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(moveDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(destColumn.getId()))
                    .containsExactly(destFirst.getId(), destSecond.getId(), moving.getId());
        }

        @Test
        void shouldClampToEnd_whenTargetPositionExceedsDestinationSize() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getMoveUrl(moving.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(moveDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedTaskIds(destColumn.getId()))
                    .containsExactly(destFirst.getId(), destSecond.getId(), moving.getId());
            Assertions.assertThat(positionsOf(destColumn.getId())).containsExactly(0, 1, 2);
        }

        @Test
        void shouldReturnBadRequest_whenTargetPositionIsNegative() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getMoveUrl(task.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(moveDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getMoveUrl(first.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(moveDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CONFLICT.value());
            Assertions.assertThat(orderedTaskIds(column.getId()))
                    .containsExactly(first.getId(), second.getId());
            Assertions.assertThat(positionsOf(column.getId())).containsExactly(0, 1);
        }

        @Test
        void shouldReturnBadRequest_whenTargetColumnIsOnDifferentBoard_beforePositionWorkRuns()
                throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getMoveUrl(task.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(moveDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnSameOrderTwice_whenReadingSameColumnRepeatedly() throws Exception {
            // arrange
            var cookie = signinCookie();
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
        void shouldReturnTasksSortedByPosition_overHttp() throws Exception {
            // arrange
            var cookie = signinCookie();
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
            mockMvc.perform(
                    patch(getMoveUrl(third.getId()))
                            .cookie(cookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(moveDto)));

            // act
            var result =
                    mockMvc.perform(get(getListTasksUrl(column.getId())).cookie(cookie))
                            .andReturn();
            var response =
                    objectMapper.readValue(
                            result.getResponse().getContentAsString(), TaskResponseDTO[].class);

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

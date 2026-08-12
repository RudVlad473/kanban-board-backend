package com.vrudenko.kanban_board.e2e.task;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.event.TaskMovedEvent;
import com.vrudenko.kanban_board.repository.TaskRepository;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import com.vrudenko.kanban_board.support.listeners.RecordingActivityEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
public class TaskMoveTest extends AbstractAppMockMvcTest {

    @Autowired private RecordingActivityEventListener recorder;

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private TaskRepository taskRepository;

    private String getMoveUrl(String taskId) {
        return ApiPaths.TASKS + "/" + taskId + ApiPaths.MOVE;
    }

    private String getColumnTasksUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId + ApiPaths.TASKS;
    }

    private String getBoardColumnsUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS;
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

    private TaskResponseDTO[] getColumnTasks(Cookie cookie, String boardId, String columnId)
            throws Exception {
        var result =
                mockMvc.perform(get(getColumnTasksUrl(boardId, columnId)).cookie(cookie))
                        .andReturn();
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), TaskResponseDTO[].class);
    }

    private List<TaskMovedEvent> recordedMovedEvents() {
        return recorder.getRecorded().stream()
                .filter(TaskMovedEvent.class::isInstance)
                .map(TaskMovedEvent.class::cast)
                .toList();
    }

    // POST endpoint (add task to column): ColumnController's mapping, no /tasks suffix -- named
    // distinctly from getColumnTasksUrl(boardId, columnId) above (the GET-list endpoint) to avoid
    // an overload pair with two different HTTP semantics behind the same name (carried over from
    // quick task 260812-eg8's TaskOrderingTest.MoveToColumn split).
    private String getAddTaskUrl(String columnId) {
        return ApiPaths.BOARDS
                + "/"
                + mockPopulatedBoard.getId()
                + ApiPaths.COLUMNS
                + "/"
                + columnId;
    }

    private ColumnResponseDTO createEmptyColumn(Cookie cookie) throws Exception {
        return createColumnOnBoard(cookie, mockPopulatedBoard.getId());
    }

    private TaskResponseDTO createTaskInColumn(Cookie cookie, String columnId) throws Exception {
        var result =
                mockMvc.perform(
                                post(getAddTaskUrl(columnId))
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
     * Tasks of {@code columnId}, sorted by the same {@code (position, id)} total order the
     * production read path applies.
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

    @Test
    void move_toColumnOnSameBoard_succeedsAndAnnouncesTaskMovedEvent() throws Exception {
        // arrange
        recorder.clear();
        Cookie cookie = signinCookie();
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
        var result =
                mockMvc.perform(
                                patch(getMoveUrl(taskId))
                                        .cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(moveDto)))
                        .andReturn();

        // assert
        Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        var responseBody =
                objectMapper.readValue(
                        result.getResponse().getContentAsString(), TaskResponseDTO.class);
        Assertions.assertThat(responseBody.getVersion()).isGreaterThan(startingVersion);

        // act
        var targetColumnTasks = getColumnTasks(cookie, boardId, targetColumnId);
        var sourceColumnTasks = getColumnTasks(cookie, boardId, sourceColumnId);

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

        // The 8 tests below are carried over from TaskOrderingTest.MoveToColumn (quick task
        // 260812-eg8, D-03 SPLIT disposition): they assert exact position VALUES via
        // TaskRepository, a property none of the sibling nested groups in this class assert,
        // rather than duplicating them. One near-identical test,
        // shouldReturnBadRequest_whenTargetColumnIsOnDifferentBoard_beforePositionWorkRuns, was
        // dropped as a genuine duplicate of CrossBoardTarget's own test below.

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
                    mockMvc.perform(
                                    get(getColumnTasksUrl(
                                                    mockPopulatedBoard.getId(), column.getId()))
                                            .cookie(cookie))
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

        @Nested
        class StaleVersion {
            @Test
            void shouldReturnConflict_whenVersionIsStale_andStaysRejectedOnRetry()
                    throws Exception {
                // arrange
                var cookie = signinCookie();
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
                var firstResult =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(firstMove)))
                                .andReturn();

                // assert
                Assertions.assertThat(firstResult.getResponse().getStatus())
                        .isEqualTo(HttpStatus.OK.value());

                recorder.clear();

                // act: second move still holding the pre-move version is rejected
                var secondResult =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(staleMove)))
                                .andReturn();

                // assert
                Assertions.assertThat(secondResult.getResponse().getStatus())
                        .isEqualTo(HttpStatus.CONFLICT.value());

                // act: retrying the same stale move again, without refetching, must still be
                // rejected
                var retryResult =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(staleMove)))
                                .andReturn();

                // assert
                Assertions.assertThat(retryResult.getResponse().getStatus())
                        .isEqualTo(HttpStatus.CONFLICT.value());

                // assert: the task's column is still the first move's target — rejected attempts
                // changed nothing
                var firstTargetTasks = getColumnTasks(cookie, boardId, firstTargetColumnId);
                var secondTargetTasks = getColumnTasks(cookie, boardId, secondTargetColumnId);

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
            void shouldAcceptFirstWriter_andRejectSecondWriter_whenBothStartFromSameVersion()
                    throws Exception {
                // arrange
                recorder.clear();
                var cookie = signinCookie();
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
                var firstResult =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(firstMove)))
                                .andReturn();
                var secondResult =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(
                                                                secondMove)))
                                .andReturn();

                // assert: first wins, second is rejected — never a silent overwrite
                Assertions.assertThat(firstResult.getResponse().getStatus())
                        .isEqualTo(HttpStatus.OK.value());
                Assertions.assertThat(secondResult.getResponse().getStatus())
                        .isEqualTo(HttpStatus.CONFLICT.value());

                var firstTargetTasks = getColumnTasks(cookie, boardId, firstTargetColumnId);
                var secondTargetTasks = getColumnTasks(cookie, boardId, secondTargetColumnId);

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
            void shouldReturnBadRequest_whenTargetColumnIsOnDifferentBoardOwnedBySameUser()
                    throws Exception {
                // arrange
                recorder.clear();
                var cookie = signinCookie();
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
                var result =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(moveDto)))
                                .andReturn();

                // assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST.value());

                var sourceColumnTasks = getColumnTasks(cookie, boardId, sourceColumnId);

                Assertions.assertThat(sourceColumnTasks)
                        .extracting(TaskResponseDTO::getId)
                        .contains(taskId);
                Assertions.assertThat(recordedMovedEvents()).isEmpty();
            }
        }

        @Nested
        class UnownedTarget {
            @Test
            void shouldReturnForbidden_whenTargetColumnOwnedByAnotherUser() throws Exception {
                // arrange
                recorder.clear();
                var cookie = signinCookie();
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
                var result =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(moveDto)))
                                .andReturn();

                // assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN.value());

                var sourceColumnTasks = getColumnTasks(cookie, boardId, sourceColumnId);

                Assertions.assertThat(sourceColumnTasks)
                        .extracting(TaskResponseDTO::getId)
                        .contains(taskId);
                Assertions.assertThat(recordedMovedEvents()).isEmpty();
            }
        }

        @Nested
        class MissingVersion {
            @Test
            void shouldReturnBadRequest_whenVersionIsMissing() throws Exception {
                // arrange
                var cookie = signinCookie();
                var taskId = mockPopulatedTask.getId();
                var targetColumnId = mockColumns.getFirst().getId();

                var moveDtoWithoutVersion =
                        MoveTaskRequestDTO.builder().targetColumnId(targetColumnId).build();

                // act
                var result =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        objectMapper.writeValueAsString(
                                                                moveDtoWithoutVersion)))
                                .andReturn();

                // assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST.value());
            }
        }

        @Nested
        class UnknownIds {
            @Test
            void shouldReturnNotFound_whenTaskIdIsUnknown() throws Exception {
                // arrange
                var cookie = signinCookie();
                var unknownTaskId = UUID.randomUUID().toString();
                var targetColumnId = mockColumns.getFirst().getId();

                var moveDto =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(targetColumnId)
                                .version(1L)
                                .build();

                // act
                var result =
                        mockMvc.perform(
                                        patch(getMoveUrl(unknownTaskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(moveDto)))
                                .andReturn();

                // assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND.value());
            }

            @Test
            void shouldReturnNotFound_whenTargetColumnIdIsUnknown() throws Exception {
                // arrange
                var cookie = signinCookie();
                var taskId = mockPopulatedTask.getId();
                var startingVersion = mockPopulatedTask.getVersion();
                var unknownColumnId = UUID.randomUUID().toString();

                var moveDto =
                        MoveTaskRequestDTO.builder()
                                .targetColumnId(unknownColumnId)
                                .version(startingVersion)
                                .build();

                // act
                var result =
                        mockMvc.perform(
                                        patch(getMoveUrl(taskId))
                                                .cookie(cookie)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(objectMapper.writeValueAsString(moveDto)))
                                .andReturn();

                // assert
                Assertions.assertThat(result.getResponse().getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND.value());
            }
        }
    }
}

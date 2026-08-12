package com.vrudenko.kanban_board.e2e.subtask;

import java.util.Arrays;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.service.ColumnService;
import com.vrudenko.kanban_board.service.TaskService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Tracer proving GAP-06 end to end: a PUT to the subtask update route runs through the controller,
 * DTO validation, the ownership chain, the service's explicit version-compare-then-409-then-flush
 * guard, and back out through {@link com.vrudenko.kanban_board.handler.GlobalExceptionHandler}.
 * Modeled on {@code e2e.task.TaskLockingTest} and {@code e2e.column.ColumnLockingTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SubtaskLockingTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

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
        void shouldReturnOkWithIncrementedVersion_whenVersionIsCurrent() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
                    mockMvc.perform(
                                    put(url).cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(updateDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var responseBody =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(), SubtaskResponseDTO.class);
            Assertions.assertThat(responseBody.getVersion()).isEqualTo(startingVersion + 1);
        }

        @Test
        void shouldReturnConflictAndLeaveStateUnchanged_whenVersionIsStale() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
                    mockMvc.perform(
                                    put(url).cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(firstUpdate)))
                            .andReturn();

            // assert
            Assertions.assertThat(firstResponse.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());

            // act: second PUT still holding the stale starting version is rejected
            var staleResponse =
                    mockMvc.perform(
                                    put(url).cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(staleUpdate)))
                            .andReturn();

            // assert
            Assertions.assertThat(staleResponse.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CONFLICT.value());

            // assert: a subsequent GET shows the original title/isCompleted unchanged
            var currentSubtasksResponse =
                    mockMvc.perform(
                                    get(ApiPaths.BOARDS
                                                    + "/"
                                                    + mockPopulatedBoard.getId()
                                                    + ApiPaths.COLUMNS
                                                    + "/"
                                                    + mockPopulatedColumn.getId()
                                                    + ApiPaths.TASKS
                                                    + "/"
                                                    + mockPopulatedTask.getId()
                                                    + ApiPaths.SUBTASKS)
                                            .cookie(cookie))
                            .andReturn();
            var currentSubtasks =
                    objectMapper.readValue(
                            currentSubtasksResponse.getResponse().getContentAsString(),
                            SubtaskResponseDTO[].class);

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
        void shouldReturnBadRequest_whenVersionIsMissing() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
                    mockMvc.perform(
                                    put(url).cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    objectMapper.writeValueAsString(
                                                            updateDtoWithoutVersion)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnForbidden_whenSubtaskOwnedByAnotherUser_andVersionCheckNeverRuns()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
            // compare, this would surface as a 409 instead of a 403
            var updateDto =
                    UpdateSubtaskRequestDTO.builder()
                            .title("Attempted cross-user update")
                            .version(otherSubtask.getVersion() + 99)
                            .build();

            // act: attempt the update as the ORIGINAL signed-in user against another user's
            // subtask
            var response =
                    mockMvc.perform(
                                    put(url).cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(updateDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }
}

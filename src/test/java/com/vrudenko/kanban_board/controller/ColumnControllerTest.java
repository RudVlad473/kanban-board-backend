package com.vrudenko.kanban_board.controller;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.ReorderColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.entity.ColumnEntity;
import com.vrudenko.kanban_board.entity.TaskEntity;
import com.vrudenko.kanban_board.repository.ColumnRepository;
import com.vrudenko.kanban_board.repository.SubtaskRepository;
import com.vrudenko.kanban_board.repository.TaskRepository;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.service.ColumnService;
import com.vrudenko.kanban_board.service.TaskService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.apache.commons.collections4.ListUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ColumnControllerTest extends AbstractAppMockMvcTest {
    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private TaskService taskService;

    @Autowired private BoardService boardService;

    @Autowired private ColumnService columnService;

    @Autowired private ColumnRepository columnRepository;

    @Autowired private TaskRepository taskRepository;

    @Autowired private SubtaskRepository subtaskRepository;

    private String getColumnsPrefix(String boardId) {
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

    private ColumnResponseDTO createColumnOnBoard(Cookie cookie, String boardId) throws Exception {
        var result =
                mockMvc.perform(
                                post(getColumnsPrefix(boardId))
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

    private TaskResponseDTO createTaskInColumn(Cookie cookie, String boardId, String columnId)
            throws Exception {
        var result =
                mockMvc.perform(
                                post(getColumnsPrefix(boardId) + "/" + columnId)
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
    class FindAllByBoardId {
        @Test
        void testWithAuthenticatedUser_shouldReturnColumns_whenColumnsExist() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var url = getColumnsPrefix(boardId);
            // Use the columns associated with mockPopulatedBoard from AbstractAppTest
            var expectedColumns =
                    objectMapper.writeValueAsString(
                            ListUtils.union(mockColumns, List.of(mockPopulatedColumn)));

            // Act & Assert
            mockMvc.perform(get(url).with(user(userId)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(expectedColumns));
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnEmptyList_whenNoColumnsExistForBoard()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            // Use one of the boards from mockEmptyBoards, which are set up without columns
            var boardId = mockEmptyBoards.getFirst().getId();
            var url = getColumnsPrefix(boardId);
            var expectedEmptyList = objectMapper.writeValueAsString(Collections.emptyList());

            // Act & Assert
            mockMvc.perform(get(url).with(user(userId)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(expectedEmptyList));
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnNotFound_whenBoardDoesNotExist()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var nonExistentBoardId = UUID.randomUUID().toString();
            var url = getColumnsPrefix(nonExistentBoardId);

            // Act & Assert
            // This depends on ColumnService.findAllByBoardId behavior for non-existent
            // boardId.
            // If it's designed to throw an exception that results in 404, this test is
            // valid.
            // If it returns an empty list for a non-existent board, this test should be
            // like
            // testWithAuthenticatedUser_shouldReturnEmptyList_whenNoColumnsExistForBoard
            mockMvc.perform(get(url).with(user(userId)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isNotFound()); // Or handle
            // as per
            // actual
            // service
            // behavior
        }
    }

    @Nested
    class AddTaskByColumnId {
        @Test
        void testWithAuthenticatedUser_shouldAddTask_whenColumnExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getColumnsPrefix(boardId) + "/" + columnId;
            var saveDTO =
                    SaveTaskRequestDTO.builder()
                            .title(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                            .description(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 3))
                            .build();

            // Act
            var response =
                    mockMvc.perform(
                                    post(url)
                                            .with(user(userId))
                                            .contentType(APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(saveDTO)))
                            .andDo(MockMvcResultHandlers.print())
                            .andExpect(status().isCreated())
                            .andReturn();
            var responseBody =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(), TaskResponseDTO.class);
            var createdTaskId = responseBody.getId();

            // Assert
            // this is an assertion since if no entity was found, it'll throw an error
            taskService.findById(userId, createdTaskId);
            Assertions.assertThat(responseBody.getTitle()).isEqualTo(saveDTO.getTitle());
            Assertions.assertThat(responseBody.getDescription())
                    .isEqualTo(saveDTO.getDescription());
        }

        @Test
        void testWithAuthenticatedUser_shouldThrow_whenColumnDoesntExist() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = UUID.randomUUID().toString();
            var url = getColumnsPrefix(boardId) + "/" + columnId;
            var saveDTO =
                    SaveTaskRequestDTO.builder()
                            .title(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                            .description(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 3))
                            .build();

            // Act
            mockMvc.perform(
                            post(url)
                                    .with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(saveDTO)))
                    .andDo(MockMvcResultHandlers.print())
                    // Assert
                    .andExpect(status().isNotFound());
        }

        // Carried over from TaskOrderingTest.TaskCreation (quick task 260812-eg8, D-03 SPLIT
        // disposition): this test posts to this same add-task-by-column-id route, not any
        // TaskController route, so it belongs here rather than in TaskControllerTest.
        @Test
        void shouldAssignContiguousPositions_whenCreatingThreeTasksInEmptyColumn()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var column = createColumnOnBoard(cookie, boardId);

            // act
            var first = createTaskInColumn(cookie, boardId, column.getId());
            var second = createTaskInColumn(cookie, boardId, column.getId());
            var third = createTaskInColumn(cookie, boardId, column.getId());

            // assert
            var orderedTasks =
                    taskRepository.findAllByColumnId(column.getId()).stream()
                            .sorted(
                                    Comparator.comparing(TaskEntity::getPosition)
                                            .thenComparing(TaskEntity::getId))
                            .toList();
            Assertions.assertThat(orderedTasks)
                    .extracting(TaskEntity::getId)
                    .containsExactly(first.getId(), second.getId(), third.getId());
            Assertions.assertThat(orderedTasks)
                    .extracting(TaskEntity::getPosition)
                    .containsExactly(0, 1, 2);
        }
    }

    @Nested
    class UpdateById {
        @Test
        void testWithAuthenticatedUser_shouldUpdateColumn_whenColumnExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getColumnsPrefix(boardId) + "/" + columnId;
            var updateDto =
                    UpdateColumnRequestDTO.builder()
                            .name(
                                    dataFactory.getRandomWord(
                                            ValidationConstants.MIN_COLUMN_NAME_LENGTH + 2))
                            .version(mockPopulatedColumn.getVersion())
                            .build();
            var expectedResponse =
                    ColumnResponseDTO.builder()
                            .id(columnId)
                            .name(updateDto.getName())
                            .version(mockPopulatedColumn.getVersion() + 1)
                            .position(mockPopulatedColumn.getPosition())
                            .build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnNotFound_whenColumnDoesNotExist()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var nonExistentColumnId = UUID.randomUUID().toString();
            var url = getColumnsPrefix(boardId) + "/" + nonExistentColumnId;
            var updateDto =
                    UpdateColumnRequestDTO.builder()
                            .name("Updated Column Name")
                            .version(0L)
                            .build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isNotFound())
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnBadRequest_whenNameIsBlank() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getColumnsPrefix(boardId) + "/" + columnId;
            var updateDto =
                    UpdateColumnRequestDTO.builder()
                            .name("")
                            .version(mockPopulatedColumn.getVersion())
                            .build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isBadRequest())
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnBadRequest_whenVersionIsMissing()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getColumnsPrefix(boardId) + "/" + columnId;
            var updateDto = UpdateColumnRequestDTO.builder().name("No version supplied").build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isBadRequest())
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnConflict_whenVersionIsStale() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getColumnsPrefix(boardId) + "/" + columnId;
            var staleVersion = mockPopulatedColumn.getVersion() - 1;
            var updateDto =
                    UpdateColumnRequestDTO.builder()
                            .name("Stale version update")
                            .version(staleVersion)
                            .build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isConflict())
                    .andReturn();
        }
    }

    // The three nested classes below (ColumnCreation, Reorder, DeleteById) are carried over from
    // ColumnOrderingTest and ColumnDeletionTest (quick task 260812-eg8, D-03 RELOCATE/FOLD
    // dispositions) -- both stray root-package files whose entire content targets routes this
    // class already owns. Their naming dialect (plain should<Outcome>_when<Condition>, real
    // signinCookie() auth) differs from the testWithAuthenticatedUser_...-prefixed, .with(user())
    // nested classes above; both dialects are preserved as-is per the fold's own instruction to
    // carry dialect across unchanged, not to normalise it.

    @Nested
    class ColumnCreation {
        @Test
        void shouldAssignContiguousPositions_whenCreatingThreeColumnsOnOneBoard() throws Exception {
            // arrange
            var cookie = signinCookie();
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
        void shouldMoveThirdColumnToFront_andShiftOthersDown_whenTargetPositionIsZero()
                throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getReorderUrl(boardId, third.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(reorderDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(third.getId(), first.getId(), second.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1, 2);
        }

        @Test
        void shouldClampToEnd_whenTargetPositionExceedsBoardColumnCount() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getReorderUrl(boardId, first.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(reorderDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(second.getId(), third.getId(), first.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1, 2);
        }

        @Test
        void shouldReturnBadRequest_whenTargetPositionIsNegative() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockEmptyBoards.get(0).getId();
            var column = createColumnOnBoard(cookie, boardId);

            var reorderDto =
                    ReorderColumnRequestDTO.builder()
                            .version(column.getVersion())
                            .targetPosition(-1)
                            .build();

            // act
            var response =
                    mockMvc.perform(
                                    patch(getReorderUrl(boardId, column.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(reorderDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnConflict_andLeavePositionsUnchanged_whenVersionIsStale() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getReorderUrl(boardId, first.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(reorderDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CONFLICT.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(first.getId(), second.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1);
        }

        @Test
        void shouldReturnForbidden_whenColumnBelongsToAnotherUsersBoard() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(
                                                    getReorderUrl(
                                                            mockPopulatedBoard.getId(),
                                                            otherColumn.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(reorderDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @Test
        void shouldNotChangeAnyTaskPosition_whenReorderingAColumn() throws Exception {
            // arrange
            var cookie = signinCookie();
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
                    mockMvc.perform(
                                    patch(getReorderUrl(boardId, second.getId()))
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(reorderDto)))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
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

        // Merged from ColumnDeletionTest.DeleteById (cascade-delete correctness, via direct
        // repository queries) and ColumnOrderingTest.DeleteById (position-contiguity after a
        // mid-list delete) -- same nested-class name in both source files, but different
        // properties of the same DELETE route; neither duplicates the other (D-03 disposition
        // table, row 4/5).

        @Test
        void
                shouldReturnOkAndCascadeDeleteTasksAndSubtasks_andLeaveSiblingColumnUntouched_whenColumnIsNonEmpty()
                        throws Exception {
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

            Cookie cookie = signinCookie();
            var url = getColumnsPrefix(mockPopulatedBoard.getId()) + "/" + targetColumnId;

            // act
            var response = mockMvc.perform(delete(url).cookie(cookie)).andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());

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
        void shouldReturnOk_whenColumnIsEmpty() throws Exception {
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

            Cookie cookie = signinCookie();
            var url = getColumnsPrefix(mockPopulatedBoard.getId()) + "/" + emptyColumn.getId();

            // act
            var response = mockMvc.perform(delete(url).cookie(cookie)).andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(columnRepository.findById(emptyColumn.getId())).isEmpty();
        }

        @Test
        void shouldReturnForbiddenAndDeleteNothing_whenColumnBelongsToAnotherUser()
                throws Exception {
            // arrange
            var otherUser = createUser();
            var otherUsersColumn =
                    createColumnForUser(
                            otherUser.getId(),
                            dataFactory.getRandomWord(
                                    ValidationConstants.MIN_BOARD_NAME_LENGTH + 4),
                            dataFactory.getRandomWord(ValidationConstants.MIN_COLUMN_NAME_LENGTH));

            Cookie cookie = signinCookie();
            var url = getColumnsPrefix(mockPopulatedBoard.getId()) + "/" + otherUsersColumn.getId();

            // act: signed in as the original owning user, targeting another user's column
            var response = mockMvc.perform(delete(url).cookie(cookie)).andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(columnRepository.findById(otherUsersColumn.getId())).isPresent();
        }

        @Test
        void shouldReturnNotFound_whenColumnDoesNotExist() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var url = getColumnsPrefix(mockPopulatedBoard.getId()) + "/" + UUID.randomUUID();

            // act
            var response = mockMvc.perform(delete(url).cookie(cookie)).andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }

        @Test
        void shouldLeaveSurvivingColumnsContiguousFromZero_whenDeletingAMiddleColumn()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockEmptyBoards.get(0).getId();
            var first = createColumnOnBoard(cookie, boardId);
            var middle = createColumnOnBoard(cookie, boardId);
            var last = createColumnOnBoard(cookie, boardId);

            // act
            var response =
                    mockMvc.perform(
                                    delete(getColumnsPrefix(boardId) + "/" + middle.getId())
                                            .cookie(cookie))
                            .andReturn();

            // assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(orderedColumnIds(boardId))
                    .containsExactly(first.getId(), last.getId());
            Assertions.assertThat(positionsOf(boardId)).containsExactly(0, 1);
        }
    }
}

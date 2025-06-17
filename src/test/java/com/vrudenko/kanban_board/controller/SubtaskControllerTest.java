package com.vrudenko.kanban_board.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SubtaskControllerTest extends AbstractAppTest {
    private String getSubtaskPrefix(String boardId, String columnId, String taskId) {
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

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Nested
    class FindAllByUserId {
        @Test
        void testWithAuthenticatedUser_shouldReturn_whenSubtasksExist() throws Exception {
            // Arrange
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var taskId = mockPopulatedTask.getId();
            var userId = getOwningUser().getId();
            var allSubtasks = objectMapper.writeValueAsString(mockSubtasks);

            // Act
            // Assert
            mockMvc.perform(get(getSubtaskPrefix(boardId, columnId, taskId)).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(allSubtasks))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnEmptyList_whenNoTasksExist() throws Exception {
            // Arrange
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockEmptyBoards.getFirst().getId();
            var taskId = mockTasks.getFirst().getId();
            var userId = getOwningUser().getId();

            // Act
            // Assert
            mockMvc.perform(get(getSubtaskPrefix(boardId, columnId, taskId)).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"))
                    .andReturn();
        }
    }

    @Nested
    class DeleteById {
        @Test
        void testWithAuthenticatedUser_shouldDeleteSubtask_whenTaskExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var subtaskId = mockSubtasks.getFirst().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();

            var url = getSubtaskPrefix(boardId, columnId, taskId) + "/" + subtaskId;

            // Act
            // Assert
            mockMvc.perform(delete(url).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andReturn();
        }

        // Consider adding a test for when the board does not exist,
        // or when a user tries to delete a board they do not own,
        // depending on the desired behavior and service implementation.
    }

    @Nested
    class UpdateById {
        @Test
        void testWithAuthenticatedUser_shouldUpdateTitleOnly_whenSubtaskExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var subtaskId = mockSubtasks.getFirst().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getSubtaskPrefix(boardId, columnId, taskId) + "/" + subtaskId;
            var updateDto =
                    UpdateSubtaskRequestDTO.builder()
                            .title(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                            .build();
            var expectedResponse =
                    SubtaskResponseDTO.builder()
                            .id(subtaskId)
                            .title(updateDto.getTitle())
                            .isCompleted(false)
                            .build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldUpdateIsCompletedOnly_whenSubtaskExists()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var subtaskId = mockSubtasks.getFirst().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getSubtaskPrefix(boardId, columnId, taskId) + "/" + subtaskId;
            var updateDto = UpdateSubtaskRequestDTO.builder().isCompleted(true).build();
            var expectedResponse =
                    Map.of("id", subtaskId, "isCompleted", updateDto.getIsCompleted());

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldUpdateAllFields_whenTaskExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var subtaskId = mockSubtasks.getFirst().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getSubtaskPrefix(boardId, columnId, taskId) + "/" + subtaskId;
            var updateDto =
                    UpdateSubtaskRequestDTO.builder()
                            .title(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                            .isCompleted(true)
                            .build();
            var expectedResponse =
                    Map.ofEntries(
                            Map.entry("id", subtaskId),
                            Map.entry("title", updateDto.getTitle()),
                            Map.entry("isCompleted", updateDto.getIsCompleted()));

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnNotFound_whenSubtaskDoesntExist()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var nonExistentSubtaskId = UUID.randomUUID().toString();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getSubtaskPrefix(boardId, columnId, taskId) + "/" + nonExistentSubtaskId;
            var updateDto =
                    UpdateSubtaskRequestDTO.builder()
                            .title(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                            .build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isNotFound())
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var taskId = mockPopulatedTask.getId();
            var subtaskId = mockSubtasks.getFirst().getId();
            var url = getSubtaskPrefix(boardId, columnId, taskId) + "/" + subtaskId;
            var updateDto = SaveSubtaskRequestDTO.builder().title("").build();

            // Act
            // Assert
            mockMvc.perform(
                            put(url).with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andReturn();
        }
    }
}

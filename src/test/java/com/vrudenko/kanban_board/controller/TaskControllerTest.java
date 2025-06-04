package com.vrudenko.kanban_board.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import java.util.List;
import java.util.UUID;
import org.apache.commons.collections4.ListUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TaskControllerTest extends AbstractAppTest {
    private String getTaskPrefix(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId + ApiPaths.TASKS;
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    class FindAllByUserId {
        @Test
        void testWithAuthenticatedUser_shouldReturn_whenTasksExist() throws Exception {
            // Arrange
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var userId = getOwningUser().getId();
            var allTasks = objectMapper.writeValueAsString(ListUtils.union(List.of(mockPopulatedTask), mockTasks));

            // Act
            // Assert
            mockMvc.perform(get(getTaskPrefix(boardId, columnId)).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(allTasks))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnEmptyList_whenNoTasksExist() throws Exception {
            // Arrange
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockEmptyBoards.getFirst().getId();
            var userId = getNoBoardsUser().getId();

            // Act
            // Assert
            mockMvc.perform(get(getTaskPrefix(boardId, columnId)).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]"))
                    .andReturn();
        }
    }

    @Nested
    class DeleteById {
        @Test
        void testWithAuthenticatedUser_shouldDeleteBoard_whenTaskExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();

            var url = getTaskPrefix(boardId, columnId) + "/" + taskId;

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
        void testWithAuthenticatedUser_shouldUpdateTitleOnly_whenTaskExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getTaskPrefix(boardId, columnId) + "/" + taskId;
            var updateDto =
                    UpdateTaskRequestDTO.builder().title("Updated Task Name").build();
            var expectedResponse = TaskResponseDTO.builder()
                    .id(taskId)
                    .title(updateDto.getTitle())
                    .build(); // Columns preservation would need to be checked differently or

            // Act
            // Assert
            mockMvc.perform(put(url).with(user(userId))
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldUpdateDescriptionOnly_whenTaskExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var taskId = mockPopulatedTask.getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getTaskPrefix(boardId, columnId) + "/" + taskId;
            var updateDto = UpdateTaskRequestDTO.builder()
                    .description("Updated Task Description")
                    .build();
            var expectedResponse = TaskResponseDTO.builder()
                    .id(taskId)
                    .description(updateDto.getDescription())
                    .build(); // Columns preservation would need to be checked differently or

            // Act
            // Assert
            mockMvc.perform(put(url).with(user(userId))
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
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getTaskPrefix(boardId, columnId) + "/" + taskId;
            var updateDto = UpdateTaskRequestDTO.builder()
                    .title(dataFactory.getRandomText(ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                    .description(dataFactory.getRandomText(ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 3))
                    .build();
            var expectedResponse = TaskResponseDTO.builder()
                    .id(taskId)
                    .title(updateDto.getTitle())
                    .description(updateDto.getDescription())
                    .build(); // Columns preservation would need to be checked differently or

            // Act
            // Assert
            mockMvc.perform(put(url).with(user(userId))
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)))
                    .andReturn();
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnNotFound_whenTaskDoesNotExist() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var nonExistentTaskId = UUID.randomUUID().toString();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var url = getTaskPrefix(boardId, columnId) + "/" + nonExistentTaskId;
            var updateDto =
                    SaveBoardRequestDTO.builder().name("Updated Board Name").build();

            // Act
            // Assert
            mockMvc.perform(put(url).with(user(userId))
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
            var url = getTaskPrefix(boardId, columnId) + "/" + taskId;
            // Assuming blank name is invalid
            var updateDto = SaveTaskRequestDTO.builder().title("").build();

            // Act
            // Assert
            mockMvc.perform(put(url).with(user(userId))
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateDto)))
                    .andDo(print())
                    .andExpect(status().isBadRequest())
                    .andReturn();
        }
    }
}

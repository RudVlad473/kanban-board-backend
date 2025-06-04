package com.vrudenko.kanban_board.controller;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.service.TaskService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.apache.commons.collections4.ListUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class ColumnControllerTest extends AbstractAppTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskService taskService;

    private String getColumnsPrefix(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS;
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
                    objectMapper.writeValueAsString(ListUtils.union(mockColumns, List.of(mockPopulatedColumn)));

            // Act & Assert
            mockMvc.perform(get(url).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(expectedColumns));
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnEmptyList_whenNoColumnsExistForBoard() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            // Use one of the boards from mockEmptyBoards, which are set up without columns
            var boardId = mockEmptyBoards.getFirst().getId();
            var url = getColumnsPrefix(boardId);
            var expectedEmptyList = objectMapper.writeValueAsString(Collections.emptyList());

            // Act & Assert
            mockMvc.perform(get(url).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().json(expectedEmptyList));
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnNotFound_whenBoardDoesNotExist() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var nonExistentBoardId = UUID.randomUUID().toString();
            var url = getColumnsPrefix(nonExistentBoardId);

            // Act & Assert
            // This depends on ColumnService.findAllByBoardId behavior for non-existent boardId.
            // If it's designed to throw an exception that results in 404, this test is valid.
            // If it returns an empty list for a non-existent board, this test should be like
            // testWithAuthenticatedUser_shouldReturnEmptyList_whenNoColumnsExistForBoard
            mockMvc.perform(get(url).with(user(userId)))
                    .andDo(print())
                    .andExpect(status().isNotFound()); // Or handle as per actual service behavior
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
            var saveDTO = SaveTaskRequestDTO.builder()
                    .title(dataFactory.getRandomText(ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                    .description(dataFactory.getRandomText(ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 3))
                    .build();

            // Act
            var response = mockMvc.perform(post(url)
                            .with(user(userId))
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(saveDTO)))
                    .andDo(print())
                    .andExpect(status().isCreated())
                    .andReturn();
            var responseBody =
                    objectMapper.readValue(response.getResponse().getContentAsString(), TaskResponseDTO.class);
            var createdTaskId = responseBody.getId();

            // Assert
            // this is an assertion since if no entity was found, it'll throw an error
            taskService.findById(userId, createdTaskId);
            Assertions.assertThat(responseBody.getTitle()).isEqualTo(saveDTO.getTitle());
            Assertions.assertThat(responseBody.getDescription()).isEqualTo(saveDTO.getDescription());
        }

        @Test
        void testWithAuthenticatedUser_shouldThrow_whenColumnDoesntExist() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var columnId = UUID.randomUUID().toString();
            var url = getColumnsPrefix(boardId) + "/" + columnId;
            var saveDTO = SaveTaskRequestDTO.builder()
                    .title(dataFactory.getRandomText(ValidationConstants.MIN_TASK_TITLE_LENGTH + 3))
                    .description(dataFactory.getRandomText(ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH + 3))
                    .build();

            // Act
            mockMvc.perform(post(url)
                            .with(user(userId))
                            .contentType(APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(saveDTO)))
                    .andDo(print())
                    // Assert
                    .andExpect(status().isNotFound());
        }
    }
}

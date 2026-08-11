package com.vrudenko.kanban_board.controller;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.service.TaskService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.collections4.ListUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ColumnControllerTest extends AbstractAppTest {
    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private TaskService taskService;

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
                    objectMapper.writeValueAsString(
                            ListUtils.union(mockColumns, List.of(mockPopulatedColumn)));

            // Act & Assert
            mockMvc.perform(get(url).with(user(userId)))
                    .andDo(print())
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
                    .andDo(print())
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
                    .andDo(print())
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
                            .andDo(print())
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
                    .andDo(print())
                    // Assert
                    .andExpect(status().isNotFound());
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
                    .andDo(print())
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
                    .andDo(print())
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
                    .andDo(print())
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
                    .andDo(print())
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
                    .andDo(print())
                    .andExpect(status().isConflict())
                    .andReturn();
        }
    }
}

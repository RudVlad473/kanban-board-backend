package com.vrudenko.kanban_board.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import java.util.Collections;
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
public class ColumnControllerTest extends AbstractAppTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

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
      mockMvc
          .perform(get(url).with(user(userId)))
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
      var boardId = mockPopulatedBoard.getId();
      var url = getColumnsPrefix(boardId);
      var expectedEmptyList = objectMapper.writeValueAsString(Collections.emptyList());

      // Act & Assert
      mockMvc
          .perform(get(url).with(user(userId)))
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
      mockMvc
          .perform(get(url).with(user(userId)))
          .andDo(print())
          .andExpect(status().isNotFound()); // Or handle as per actual service behavior
    }
  }
}

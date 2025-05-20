package com.vrudenko.kanban_board.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import java.util.List;
import org.apache.commons.collections4.ListUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
public class BoardControllerTest extends AbstractAppTest {

  private String getBoardPrefix() {
    return ApiPaths.BOARDS;
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @Nested
  class FindAllByUserId {
    @Test
    void testWithAuthenticatedUser_shouldReturn_whenBoardsExist() throws Exception {
      // Arrange
      var userId = getOwningUser().getId();
      var allBoards =
          objectMapper.writeValueAsString(
              ListUtils.union(List.of(mockPopulatedBoard), mockEmptyBoards));

      // Act
      // Assert
      mockMvc
          .perform(get(getBoardPrefix()).with(user(userId)))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(content().json(allBoards))
          .andReturn();
    }

    @Test
    void testWithAuthenticatedUser_shouldReturnEmptyList_whenNoBoardsExist() throws Exception {
      // Arrange
      var userId = getNoBoardsUser().getId();

      // Act
      // Assert
      mockMvc
          .perform(get(getBoardPrefix()).with(user(userId)))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(content().json("[]"))
          .andReturn();
    }
  }

  @Nested
  class DeleteById {
    @Test
    void testWithAuthenticatedUser_shouldDeleteBoard_whenBoardExists() throws Exception {
      // Arrange
      var userId = getOwningUser().getId();
      var boardId = mockPopulatedBoard.getId();
      var url = getBoardPrefix() + "/" + boardId;

      // Act
      // Assert
      mockMvc
          .perform(delete(url).with(user(userId)))
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
    void testWithAuthenticatedUser_shouldUpdateBoard_whenBoardExists() throws Exception {
      // Arrange
      var userId = getOwningUser().getId();
      var boardId = mockPopulatedBoard.getId();
      var url = getBoardPrefix() + "/" + boardId;
      var updateDto = SaveBoardRequestDTO.builder().name("Updated Board Name").build();
      var expectedResponse =
          BoardResponseDTO.builder()
              .id(boardId)
              .name(updateDto.getName())
              .build(); // Columns preservation would need to be checked differently or
                        // BoardResponseDTO updated to include them with accessible methods.

      // Act
      // Assert
      mockMvc
          .perform(
              put(url)
                  .with(user(userId))
                  .contentType(APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(updateDto)))
          .andDo(print())
          .andExpect(status().isOk())
          .andExpect(content().json(objectMapper.writeValueAsString(expectedResponse)))
          .andReturn();
    }

    @Test
    void testWithAuthenticatedUser_shouldReturnNotFound_whenBoardDoesNotExist() throws Exception {
      // Arrange
      var userId = getOwningUser().getId();
      var nonExistentBoardId = java.util.UUID.randomUUID().toString();
      var url = getBoardPrefix() + "/" + nonExistentBoardId;
      var updateDto = SaveBoardRequestDTO.builder().name("Updated Board Name").build();

      // Act
      // Assert
      mockMvc
          .perform(
              put(url)
                  .with(user(userId))
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
      var url = getBoardPrefix() + "/" + boardId;
      // Assuming blank name is invalid
      var updateDto = SaveBoardRequestDTO.builder().name("").build();

      // Act
      // Assert
      mockMvc
          .perform(
              put(url)
                  .with(user(userId))
                  .contentType(APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(updateDto)))
          .andDo(print())
          .andExpect(status().isBadRequest())
          .andReturn();
    }
  }
}

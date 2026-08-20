package com.vrudenko.kanban_board.controller;

import java.util.List;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.service.ColumnService;
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
import org.springframework.test.web.servlet.result.MockMvcResultHandlers;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class BoardControllerTest extends AbstractAppTest {

    private String getBoardPrefix() {
        return ApiPaths.BOARDS;
    }

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private ColumnService columnService;

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
            mockMvc.perform(get(getBoardPrefix()).with(user(userId)))
                    .andDo(MockMvcResultHandlers.print())
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
            mockMvc.perform(get(getBoardPrefix()).with(user(userId)))
                    .andDo(MockMvcResultHandlers.print())
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
            mockMvc.perform(delete(url).with(user(userId)))
                    .andDo(MockMvcResultHandlers.print())
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
            var updateDto =
                    UpdateBoardRequestDTO.builder()
                            .name("Updated Board Name")
                            .version(mockPopulatedBoard.getVersion())
                            .build();

            // Act
            var response =
                    mockMvc.perform(
                                    put(url).with(user(userId))
                                            .contentType(APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(updateDto)))
                            .andDo(MockMvcResultHandlers.print())
                            .andExpect(status().isOk())
                            .andReturn();

            // Assert
            var responseBody =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(), BoardResponseDTO.class);
            Assertions.assertThat(responseBody.getId()).isEqualTo(boardId);
            Assertions.assertThat(responseBody.getName()).isEqualTo(updateDto.getName());
            Assertions.assertThat(responseBody.getVersion())
                    .isNotEqualTo(mockPopulatedBoard.getVersion());
        }

        @Test
        void testWithAuthenticatedUser_shouldReturnNotFound_whenBoardDoesNotExist()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var nonExistentBoardId = java.util.UUID.randomUUID().toString();
            var url = getBoardPrefix() + "/" + nonExistentBoardId;
            // Any non-null version satisfies validation here -- ownership/findById throws before
            // the version-compare guard ever runs against a board that doesn't exist.
            var updateDto =
                    UpdateBoardRequestDTO.builder().name("Updated Board Name").version(0L).build();

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
        void testWithAuthenticatedUser_shouldReturnBadRequest_whenDataIsInvalid() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardPrefix() + "/" + boardId;
            // Assuming blank name is invalid
            var updateDto =
                    UpdateBoardRequestDTO.builder()
                            .name("")
                            .version(mockPopulatedBoard.getVersion())
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
        void testWithAuthenticatedUser_shouldReturnBadRequest_whenNameIsWhitespaceOnly()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardPrefix() + "/" + boardId;
            var updateDto =
                    UpdateBoardRequestDTO.builder()
                            .name("   ")
                            .version(mockPopulatedBoard.getVersion())
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

        // Single-endpoint HTTP contract coverage (docs/CODE_STYLE.md rule 4): a version-less PUT
        // body is a request-shape concern belonging here, distinct from BoardLockingTest's e2e
        // proof of the 409 stale-write conflict itself.
        @Test
        void testWithAuthenticatedUser_shouldReturnBadRequest_whenVersionIsMissing()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardPrefix() + "/" + boardId;
            var updateDto = UpdateBoardRequestDTO.builder().name("Updated Board Name").build();

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
    }

    @Nested
    class AddColumnByBoardId {
        @Test
        void testWithAuthenticatedUser_shouldAddColumn_whenBoardExists() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardPrefix() + "/" + boardId + ApiPaths.COLUMNS;
            var saveDTO =
                    SaveColumnRequestDTO.builder()
                            .name(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_COLUMN_NAME_LENGTH + 3))
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
                            response.getResponse().getContentAsString(), ColumnResponseDTO.class);

            var createdColumnId = responseBody.getId();

            // Assert
            // this is an assertion since if no entity was found, it'll throw an error
            columnService.findById(userId, createdColumnId);
            Assertions.assertThat(responseBody.getName()).isEqualTo(saveDTO.getName());
            Assertions.assertThat(response.getResponse().getHeader("Location")).isNotBlank();
        }

        @Test
        void testWithAuthenticatedUser_shouldThrow_whenBoardDoesntExist() throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = UUID.randomUUID().toString();
            var url = getBoardPrefix() + "/" + boardId + ApiPaths.COLUMNS;
            var saveDTO =
                    SaveColumnRequestDTO.builder()
                            .name(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_COLUMN_NAME_LENGTH + 3))
                            .build();

            // Act
            mockMvc.perform(
                            post(url)
                                    .with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(saveDTO)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isNotFound());
        }

        // Regression test for 260811-qru finding F-04: SaveColumnRequestDTO.name's @Size
        // constraint carried the wrong message constant (ValidationConstants.
        // NAME_LENGTH_VALIDATION_MESSAGE, the board-name-flavored text with board-name
        // bounds 1-64) instead of COLUMN_NAME_LENGTH_VALIDATION_MESSAGE (column bounds
        // 3-32) -- confirmed live (unlike the sibling SubtaskTitle mismatch, F-05, this
        // field is not wrapped in a composed @ReportAsSingleViolation annotation, so the
        // wrong message text really does reach the client).
        @Test
        void testWithAuthenticatedUser_shouldReturnColumnSpecificMessage_whenNameIsTooShort()
                throws Exception {
            // Arrange
            var userId = getOwningUser().getId();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardPrefix() + "/" + boardId + ApiPaths.COLUMNS;
            var saveDTO =
                    SaveColumnRequestDTO.builder()
                            .name(
                                    dataFactory.getRandomText(
                                            ValidationConstants.MIN_COLUMN_NAME_LENGTH - 1))
                            .build();

            // Act
            // Assert
            mockMvc.perform(
                            post(url)
                                    .with(user(userId))
                                    .contentType(APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(saveDTO)))
                    .andDo(MockMvcResultHandlers.print())
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.errors.name")
                                    .value(
                                            ValidationConstants
                                                    .COLUMN_NAME_LENGTH_VALIDATION_MESSAGE));
        }
    }
}

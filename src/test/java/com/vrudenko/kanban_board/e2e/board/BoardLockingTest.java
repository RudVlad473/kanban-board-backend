package com.vrudenko.kanban_board.e2e.board;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.board_dto.BoardFullResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
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

/**
 * Proves D-13/D-15 end to end: Board now shares the same explicit-version-compare concurrency model
 * as Column/Task/Subtask (no exceptions left, per this plan's success criteria). Modeled on {@link
 * com.vrudenko.kanban_board.e2e.column.ColumnLockingE2ETest} and {@link
 * com.vrudenko.kanban_board.e2e.task.TaskLockingE2ETest}'s structure, with {@code ProblemDetail}
 * fields asserted at their flattened top-level paths (matching {@code GlobalExceptionHandlerTest}'s
 * convention) rather than deserialized into a DTO, since the 409/400 cases under test are error
 * responses, not {@link BoardResponseDTO} bodies.
 *
 * <p>Deliberately named without the {@code E2ETest} suffix -- plan 07.1-07 is dropping that suffix
 * from in-process MockMvc-tier classes, and a new class should not be born needing the rename. Per
 * D-22 it carries no {@code @Tag}, so it runs in the pre-commit {@code fastTest} gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class BoardLockingTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    private String getBoardUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId;
    }

    private String getFullBoardUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.FULL;
    }

    @Nested
    class ConcurrentRename {
        @Test
        void concurrentConflictingRenames_firstSucceeds_secondReturnsConflict() throws Exception {
            // Arrange
            Cookie cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardUrl(boardId);
            var startingVersion = mockPopulatedBoard.getVersion();

            var firstUpdate =
                    UpdateBoardRequestDTO.builder()
                            .name("First writer wins")
                            .version(startingVersion)
                            .build();

            var secondUpdate =
                    UpdateBoardRequestDTO.builder()
                            .name("Second writer loses")
                            .version(startingVersion)
                            .build();

            // Act: first PUT with the just-read version succeeds and bumps the version
            var firstResponse =
                    mockMvc.perform(
                                    put(url).cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(firstUpdate)))
                            .andReturn();

            // Assert: first rename succeeds and its response body carries an incremented version
            Assertions.assertThat(firstResponse.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var firstResponseBody =
                    objectMapper.readValue(
                            firstResponse.getResponse().getContentAsString(),
                            BoardResponseDTO.class);
            Assertions.assertThat(firstResponseBody.getVersion()).isGreaterThan(startingVersion);

            // Act: second PUT still holding that same now-stale version is rejected
            mockMvc.perform(
                            put(url).cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(secondUpdate)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));

            // Act: re-submitting the same stale PUT again (without refetching) must still be
            // rejected, never silently succeed
            mockMvc.perform(
                            put(url).cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(secondUpdate)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));
        }

        @Test
        void rename_withCurrentVersion_succeedsAndReturnsIncrementedVersion() throws Exception {
            // Arrange
            Cookie cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardUrl(boardId);
            var startingVersion = mockPopulatedBoard.getVersion();

            var updateDto =
                    UpdateBoardRequestDTO.builder()
                            .name("Updated with current version")
                            .version(startingVersion)
                            .build();

            // Act
            var response =
                    mockMvc.perform(
                                    put(url).cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(updateDto)))
                            .andReturn();

            // Assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var responseBody =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(), BoardResponseDTO.class);
            Assertions.assertThat(responseBody.getVersion()).isGreaterThan(startingVersion);
        }
    }

    @Nested
    class MissingVersion {
        @Test
        void rename_withoutVersion_returnsBadRequestWithVersionFieldError() throws Exception {
            // Arrange
            Cookie cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var url = getBoardUrl(boardId);

            var updateDtoWithoutVersion =
                    UpdateBoardRequestDTO.builder().name("No version here").build();

            // Act & Assert
            mockMvc.perform(
                            put(url).cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    updateDtoWithoutVersion)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.version").exists());
        }
    }

    @Nested
    class VersionExposure {
        @Test
        void findFullById_shouldReturnBoardsOwnVersion_notOnlyNestedVersions() throws Exception {
            // Arrange
            Cookie cookie = signinCookie();

            // Act
            var response =
                    mockMvc.perform(get(getFullBoardUrl(mockPopulatedBoard.getId())).cookie(cookie))
                            .andReturn();

            // Assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var body =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(),
                            BoardFullResponseDTO.class);
            Assertions.assertThat(body.getVersion()).isNotNull();
            Assertions.assertThat(body.getVersion()).isEqualTo(mockPopulatedBoard.getVersion());
            Assertions.assertThat(body.getColumns()).isNotEmpty();
            Assertions.assertThat(body.getColumns().getFirst().getVersion()).isNotNull();
        }

        @Test
        void findAllByUserId_shouldReturnVersionOnEveryBoard() throws Exception {
            // Arrange
            Cookie cookie = signinCookie();

            // Act
            var response = mockMvc.perform(get(ApiPaths.BOARDS).cookie(cookie)).andReturn();

            // Assert
            Assertions.assertThat(response.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var boards =
                    objectMapper.readValue(
                            response.getResponse().getContentAsString(), BoardResponseDTO[].class);
            Assertions.assertThat(boards).isNotEmpty();
            Assertions.assertThat(boards)
                    .allSatisfy(b -> Assertions.assertThat(b.getVersion()).isNotNull());
        }
    }
}

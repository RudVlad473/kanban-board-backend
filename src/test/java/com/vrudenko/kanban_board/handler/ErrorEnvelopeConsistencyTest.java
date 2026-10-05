package com.vrudenko.kanban_board.handler;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins one error envelope across the {@code controller/} classes, whichever controller handles the
 * request.
 *
 * <p>A {@code @Valid @RequestBody} field violation is always {@code VALIDATION_FAILED} with a
 * per-field {@code errors} map; a {@code @PathVariable @NotBlank} violation is always {@code
 * CONSTRAINT_VIOLATION}, never a 5xx.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorEnvelopeConsistencyTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Nested
    class RequestBodyFieldValidationEnvelope {

        @Test
        void shouldReturnValidationFailedWithErrorsMap_whenBoardNameExceedsMax() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var name = "A".repeat(ValidationConstants.MAX_BOARD_NAME_LENGTH + 1);

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveBoardRequestDTO.builder()
                                                            .name(name)
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").exists())
                    .andExpect(jsonPath("$.properties").doesNotExist());
        }

        @Test
        void shouldReturnValidationFailedWithErrorsMap_whenTaskTitleExceedsMax() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var title = "A".repeat(ValidationConstants.MAX_TASK_TITLE_LENGTH + 1);
            var url = ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS + ApiPaths.COLUMN_ID;

            // act & assert
            mockMvc.perform(
                            post(url, boardId, columnId)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveTaskRequestDTO.builder()
                                                            .title(title)
                                                            .description("envelope contract test")
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.title").exists())
                    .andExpect(jsonPath("$.properties").doesNotExist());
        }
    }

    @Nested
    class PathVariableConstraintEnvelope {

        @Test
        void shouldReturnConstraintViolation_whenBoardIdPathVariableIsBlank() throws Exception {
            // arrange: URI-template substitution (not concatenation) so the space is
            // percent-encoded to %20 as a real client sends it.
            Cookie cookie = signinCookie();
            var url = ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.FULL;

            // act & assert
            mockMvc.perform(get(url, " ").cookie(cookie))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));
        }

        @Test
        void shouldReturnConstraintViolation_whenBoardIdPathVariableIsBlank_onColumnRoute()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var url = ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS;

            // act & assert
            mockMvc.perform(get(url, " ").cookie(cookie))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));
        }
    }
}

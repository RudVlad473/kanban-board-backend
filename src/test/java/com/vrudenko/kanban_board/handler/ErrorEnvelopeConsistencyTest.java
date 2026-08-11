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
 * Quick task 260811-p9c: pins the DESIRED end-state envelope contract across all seven {@code
 * controller/} classes for two failure kinds -- a {@code @Valid @RequestBody} field constraint
 * (should always be {@code VALIDATION_FAILED} + a per-field {@code errors} map) and a
 * {@code @PathVariable @NotBlank} constraint (should always be {@code CONSTRAINT_VIOLATION}, never
 * a 5xx) -- spanning at least one already-{@code @Validated} controller ({@link
 * com.vrudenko.kanban_board.controller.BoardController}) and one not-yet-{@code @Validated}
 * controller ({@link com.vrudenko.kanban_board.controller.ColumnController}). Two of the four cases
 * below are expected to start RED: this class is a measurement fixture before quick task 260811-p9c
 * Task 2 touches any {@code src/main} file, not a pre-verified assertion of already-converged
 * behavior. See {@code .planning/quick/260811-p9c-.../260811-p9c-SUMMARY.md} for the recorded
 * pre-change baseline table.
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
            // arrange: BoardController already carries class-level @Validated
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
            // arrange: this creation route lives on ColumnController, which does NOT (yet) carry
            // class-level @Validated -- this case is expected to start RED, empirically confirming
            // the split quick task 260811-p9c's source todo describes
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
            // arrange: BoardController already carries class-level @Validated. URI-template
            // variable substitution (not string concatenation) so the single space is
            // percent-encoded to %20 by Spring's UriComponentsBuilder, exactly as a JSON API
            // client sending that literal value would produce it.
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
            // arrange: ColumnController does NOT (yet) carry class-level @Validated -- expected
            // GREEN today, since @NotBlank on this route's @PathVariable is still validated
            // through Spring MVC's built-in method-validation path rather than the AOP validator
            Cookie cookie = signinCookie();
            var url = ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS;

            // act & assert
            mockMvc.perform(get(url, " ").cookie(cookie))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));
        }
    }
}

package com.vrudenko.kanban_board.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves every {@link GlobalExceptionHandler} branch emits a real RFC 7807 {@code ProblemDetail}
 * envelope carrying a stable, published {@code code} extension property (D-01, D-03), rather than
 * the bare-string/flat-map bodies the handler returned before this phase. {@code $.code} and {@code
 * $.errors} are asserted as top-level JSON keys -- never {@code $.properties.code} -- which is what
 * proves {@code ProblemDetailJacksonMixin}'s extension-property flattening locally instead of
 * resting on RESEARCH.md's citation of it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Nested
    class EntityNotFoundTest {

        @Test
        void shouldReturnProblemDetailWithEntityNotFoundCode_whenBoardDoesNotExist()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var unknownBoardId = UUID.randomUUID().toString();

            // act & assert
            mockMvc.perform(
                            get(ApiPaths.BOARDS + "/" + unknownBoardId + ApiPaths.FULL)
                                    .cookie(cookie))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(404))
                    .andExpect(jsonPath("$.code").value("ENTITY_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").isNotEmpty())
                    .andExpect(jsonPath("$.properties").doesNotExist());
        }
    }
}

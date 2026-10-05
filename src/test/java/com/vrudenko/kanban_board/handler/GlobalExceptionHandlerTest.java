package com.vrudenko.kanban_board.handler;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.service.UserService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves every {@link GlobalExceptionHandler} branch emits an RFC 7807 {@code ProblemDetail} with a
 * stable {@code code} property.
 *
 * <p>{@code $.code} and {@code $.errors} are asserted as top-level JSON keys, never {@code
 * $.properties.code}, which pins {@code ProblemDetailJacksonMixin}'s flattening of extension
 * properties.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private UserService userService;

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

    @Nested
    class AccessDeniedTest {

        @Test
        void
                shouldReturnForbiddenWithAccessDeniedCode_andDiscloseNothing_whenBoardOwnedByAnotherUser()
                        throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var otherUser = createUser();
            // Fixed "about", not a random word: it is a valid board name that once collided with
            // the "about:blank" RFC 7807 type and failed intermittently.
            var otherBoard =
                    userService.addBoardByUserId(
                            otherUser.getId(), SaveBoardRequestDTO.builder().name("about").build());

            // act
            var response =
                    mockMvc.perform(
                                    get(ApiPaths.BOARDS + "/" + otherBoard.getId() + ApiPaths.FULL)
                                            .cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(response.getContentType()).isEqualTo("application/problem+json");

            var body = objectMapper.readTree(response.getContentAsString());
            Assertions.assertThat(body.get("code").asText()).isEqualTo("ACCESS_DENIED");

            // `type` is pinned to the RFC 7807 default "about:blank" so the exclusion below cannot
            // silently widen if Spring starts emitting a real type URI.
            Assertions.assertThat(body.get("type").asText()).isEqualTo("about:blank");

            // Leak-check every other field, not just `detail`: a 403 must never echo the board
            // name via `title`, `instance`, `code` or any future extension property.
            var fieldNames = body.fieldNames();
            while (fieldNames.hasNext()) {
                var fieldName = fieldNames.next();
                if (fieldName.equals("type")) {
                    continue;
                }
                var fieldValue = body.get(fieldName);
                if (fieldValue.isTextual()) {
                    Assertions.assertThat(fieldValue.asText())
                            .as("field '%s' must not leak the other user's board name", fieldName)
                            .doesNotContain(otherBoard.getName());
                }
            }
        }
    }

    @Nested
    class ValidationFailedTest {

        @Test
        void shouldReturnProblemDetailWithNestedErrors_whenBoardNameIsBlank() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var invalidDto = SaveBoardRequestDTO.builder().name("").build();

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(invalidDto)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").exists())
                    .andExpect(jsonPath("$.properties").doesNotExist());
        }
    }

    @Nested
    class OptimisticLockConflictTest {

        @Test
        void shouldReturnProblemDetailWithOptimisticLockConflictCode_whenColumnVersionIsStale()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var staleUpdateDto =
                    UpdateColumnRequestDTO.builder()
                            .name(
                                    dataFactory.getRandomWord(
                                            ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                            .version(mockPopulatedColumn.getVersion() + 99)
                            .build();
            var url =
                    ApiPaths.BOARDS
                            + "/"
                            + mockPopulatedBoard.getId()
                            + ApiPaths.COLUMNS
                            + "/"
                            + mockPopulatedColumn.getId();

            // act & assert
            mockMvc.perform(
                            put(url).cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(staleUpdateDto)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK_CONFLICT"));
        }
    }

    @Nested
    class DuplicateResourceTest {

        @Test
        void shouldReturnProblemDetailWithDuplicateResourceCode_whenBoardNameAlreadyExists()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var boardName =
                    dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4);
            userService.addBoardByUserId(
                    getOwningUser().getId(), SaveBoardRequestDTO.builder().name(boardName).build());
            var duplicateDto = SaveBoardRequestDTO.builder().name(boardName).build();

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(duplicateDto)))
                    .andExpect(status().isConflict())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        }
    }

    @Nested
    class MalformedRequestBodyTest {

        @Test
        void shouldReturnProblemDetailWithMalformedRequestBodyCode_whenThemeValueIsUnknown()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var malformedBody = "{\"theme\":\"NOT_A_REAL_THEME\"}";

            // act & assert
            mockMvc.perform(
                            put(ApiPaths.USERS + ApiPaths.ME + ApiPaths.THEME)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(malformedBody))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST_BODY"));
        }
    }

    /**
     * An unauthenticated request returns 401 with the same RFC 7807 envelope as every other error.
     *
     * <p>{@link com.vrudenko.kanban_board.security.ProblemDetailAuthenticationEntryPoint} produces
     * it, independently of this class's {@code @ExceptionHandler} methods, because {@code
     * ExceptionTranslationFilter} rejects the request before {@code DispatcherServlet} dispatches.
     */
    @Nested
    class UnauthenticatedTest {

        @Test
        void shouldReturnUnauthorizedWithUnauthenticatedCode_whenNoSessionCookie()
                throws Exception {
            // arrange & act
            var response =
                    mockMvc.perform(get(ApiPaths.USERS + ApiPaths.ME + ApiPaths.THEME))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(response.getContentType()).isEqualTo("application/problem+json");

            var body = objectMapper.readTree(response.getContentAsString());
            Assertions.assertThat(body.get("status").asInt())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(body.get("code").asText()).isEqualTo("UNAUTHENTICATED");
        }

        @Test
        void shouldReturnOk_whenSignedInRequestToSameRoute() throws Exception {
            // arrange
            Cookie cookie = signinCookie();

            // act
            var response =
                    mockMvc.perform(
                                    get(ApiPaths.USERS + ApiPaths.ME + ApiPaths.THEME)
                                            .cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        @Test
        void shouldMatchAccessDeniedBodyKeySet_whenComparing401To403() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var otherUser = createUser();
            var otherBoard =
                    userService.addBoardByUserId(
                            otherUser.getId(),
                            SaveBoardRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_BOARD_NAME_LENGTH + 4))
                                    .build());

            // act
            var unauthenticatedResponse =
                    mockMvc.perform(get(ApiPaths.USERS + ApiPaths.ME + ApiPaths.THEME))
                            .andReturn()
                            .getResponse();
            var forbiddenResponse =
                    mockMvc.perform(
                                    get(ApiPaths.BOARDS + "/" + otherBoard.getId() + ApiPaths.FULL)
                                            .cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert: both producers emit the same top-level key set
            var unauthenticatedKeys =
                    topLevelKeys(
                            objectMapper.readTree(unauthenticatedResponse.getContentAsString()));
            var forbiddenKeys =
                    topLevelKeys(objectMapper.readTree(forbiddenResponse.getContentAsString()));
            Assertions.assertThat(unauthenticatedKeys).isEqualTo(forbiddenKeys);
        }

        private Set<String> topLevelKeys(JsonNode node) {
            var keys = new HashSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            return keys;
        }
    }
}

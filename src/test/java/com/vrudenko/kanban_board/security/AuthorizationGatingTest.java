package com.vrudenko.kanban_board.security;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ReorderColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.MoveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UpdateThemeRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.ThemePreference;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One sweep proving every protected route rejects an unauthenticated request (401) and a cross-user
 * request against another user's resource (403).
 *
 * routeTable() is the single source of truth: one row per protected route, derived from
 * the seven controller/ classes (22 routes: Board 6, Column 5, Task 4, Subtask 3, User 2,
 * TaskMove 1, Activity 1).
 *
 * Why this is the way it is: request bodies are static constants, never built from fixture
 * state. Every service resolves its target through the ownership-verified loader (
 * docs/CODE_STYLE.md rule 2) before touching any other body field, so an arbitrary valid body
 * (e.g. version = 0) still exercises the 401/403 path; field values matter only past the
 * ownership gate, which the rejected requests never reach.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class AuthorizationGatingTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private RequestMappingHandlerMapping requestMappingHandlerMapping;

    private static final String BOARDS_URL = ApiPaths.BOARDS;
    private static final String BOARD_URL = ApiPaths.BOARDS + ApiPaths.BOARD_ID;
    private static final String BOARD_FULL_URL = BOARD_URL + ApiPaths.FULL;
    private static final String BOARD_ACTIVITY_URL = BOARD_URL + ApiPaths.ACTIVITY;
    private static final String BOARD_COLUMNS_URL = BOARD_URL + ApiPaths.COLUMNS;
    private static final String COLUMN_URL = BOARD_COLUMNS_URL + ApiPaths.COLUMN_ID;
    private static final String COLUMN_REORDER_URL = COLUMN_URL + ApiPaths.REORDER;
    private static final String TASKS_URL = COLUMN_URL + ApiPaths.TASKS;
    private static final String TASK_URL = TASKS_URL + ApiPaths.TASK_ID;
    private static final String TASK_MOVE_URL = ApiPaths.TASKS + ApiPaths.TASK_ID + ApiPaths.MOVE;
    private static final String SUBTASKS_URL = TASK_URL + ApiPaths.SUBTASKS;
    private static final String SUBTASK_URL = SUBTASKS_URL + ApiPaths.SUBTASK_ID;
    private static final String USER_THEME_URL = ApiPaths.USERS + ApiPaths.ME + ApiPaths.THEME;

    // Static, fixture-independent bodies; see the class Javadoc for why arbitrary values suffice.
    private static final SaveBoardRequestDTO SAVE_BOARD_BODY =
            SaveBoardRequestDTO.builder().name("Gating Sweep Board").build();
    private static final UpdateBoardRequestDTO UPDATE_BOARD_BODY =
            UpdateBoardRequestDTO.builder().name("Gating Sweep Board").version(0L).build();
    private static final SaveColumnRequestDTO SAVE_COLUMN_BODY =
            SaveColumnRequestDTO.builder().name("Gating Sweep Column").build();
    private static final UpdateColumnRequestDTO UPDATE_COLUMN_BODY =
            UpdateColumnRequestDTO.builder().name("Gating Sweep Column").version(0L).build();
    private static final ReorderColumnRequestDTO REORDER_COLUMN_BODY =
            ReorderColumnRequestDTO.builder().version(0L).targetPosition(0).build();
    private static final SaveTaskRequestDTO SAVE_TASK_BODY =
            SaveTaskRequestDTO.builder().title("Gating Sweep Task").description("desc").build();
    private static final UpdateTaskRequestDTO UPDATE_TASK_BODY =
            UpdateTaskRequestDTO.builder().title("Gating Sweep Task").version(0L).build();
    private static final MoveTaskRequestDTO MOVE_TASK_BODY =
            MoveTaskRequestDTO.builder()
                    .targetColumnId("placeholder-target-column-id")
                    .version(0L)
                    .build();
    private static final SaveSubtaskRequestDTO SAVE_SUBTASK_BODY =
            SaveSubtaskRequestDTO.builder().title("Gating Sweep Subtask").build();
    private static final UpdateSubtaskRequestDTO UPDATE_SUBTASK_BODY =
            UpdateSubtaskRequestDTO.builder().title("Gating Sweep Subtask").version(0L).build();
    private static final UpdateThemeRequestDTO UPDATE_THEME_BODY =
            UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

    private enum PathShape {
        NONE,
        BOARD,
        BOARD_COLUMN,
        BOARD_COLUMN_TASK,
        BOARD_COLUMN_TASK_SUBTASK,
        TASK_ONLY
    }

    /**
     * One row per protected route.
     *
     * crossUserApplicable = false marks the four routes that address no other user's
     * resource by construction (GET/POST /boards, GET/PUT /users/me/theme); they
     * get a scoped-to-caller assertion in ScopedToCaller instead of being skipped.
     */
    private record RouteCase(
            String displayName,
            HttpMethod method,
            String pathTemplate,
            PathShape pathShape,
            Object body,
            boolean crossUserApplicable) {
        @Override
        public String toString() {
            return displayName;
        }
    }

    /** The four owning-user resource ids a PathShape may need, resolved at call time. */
    private record FixtureIds(String boardId, String columnId, String taskId, String subtaskId) {}

    private static final FixtureIds DUMMY_FIXTURE_IDS =
            new FixtureIds("dummy-board", "dummy-column", "dummy-task", "dummy-subtask");

    private static Object[] resolvePathVars(PathShape shape, FixtureIds ids) {
        return switch (shape) {
            case NONE -> new Object[] {};
            case BOARD -> new Object[] {ids.boardId()};
            case BOARD_COLUMN -> new Object[] {ids.boardId(), ids.columnId()};
            case BOARD_COLUMN_TASK -> new Object[] {ids.boardId(), ids.columnId(), ids.taskId()};
            case BOARD_COLUMN_TASK_SUBTASK ->
                    new Object[] {ids.boardId(), ids.columnId(), ids.taskId(), ids.subtaskId()};
            case TASK_ONLY -> new Object[] {ids.taskId()};
        };
    }

    private static List<RouteCase> routeTable() {
        return List.of(
                // BoardController -- 6
                new RouteCase(
                        "GET /boards", HttpMethod.GET, BOARDS_URL, PathShape.NONE, null, false),
                new RouteCase(
                        "POST /boards",
                        HttpMethod.POST,
                        BOARDS_URL,
                        PathShape.NONE,
                        SAVE_BOARD_BODY,
                        false),
                new RouteCase(
                        "DELETE /boards/{boardId}",
                        HttpMethod.DELETE,
                        BOARD_URL,
                        PathShape.BOARD,
                        null,
                        true),
                new RouteCase(
                        "PUT /boards/{boardId}",
                        HttpMethod.PUT,
                        BOARD_URL,
                        PathShape.BOARD,
                        UPDATE_BOARD_BODY,
                        true),
                new RouteCase(
                        "POST /boards/{boardId}/columns",
                        HttpMethod.POST,
                        BOARD_COLUMNS_URL,
                        PathShape.BOARD,
                        SAVE_COLUMN_BODY,
                        true),
                new RouteCase(
                        "GET /boards/{boardId}/full",
                        HttpMethod.GET,
                        BOARD_FULL_URL,
                        PathShape.BOARD,
                        null,
                        true),
                // ColumnController -- 5
                new RouteCase(
                        "GET /boards/{boardId}/columns",
                        HttpMethod.GET,
                        BOARD_COLUMNS_URL,
                        PathShape.BOARD,
                        null,
                        true),
                new RouteCase(
                        "POST /boards/{boardId}/columns/{columnId}",
                        HttpMethod.POST,
                        COLUMN_URL,
                        PathShape.BOARD_COLUMN,
                        SAVE_TASK_BODY,
                        true),
                new RouteCase(
                        "PUT /boards/{boardId}/columns/{columnId}",
                        HttpMethod.PUT,
                        COLUMN_URL,
                        PathShape.BOARD_COLUMN,
                        UPDATE_COLUMN_BODY,
                        true),
                new RouteCase(
                        "DELETE /boards/{boardId}/columns/{columnId}",
                        HttpMethod.DELETE,
                        COLUMN_URL,
                        PathShape.BOARD_COLUMN,
                        null,
                        true),
                new RouteCase(
                        "PATCH /boards/{boardId}/columns/{columnId}/reorder",
                        HttpMethod.PATCH,
                        COLUMN_REORDER_URL,
                        PathShape.BOARD_COLUMN,
                        REORDER_COLUMN_BODY,
                        true),
                // TaskController -- 4
                new RouteCase(
                        "GET /boards/{boardId}/columns/{columnId}/tasks",
                        HttpMethod.GET,
                        TASKS_URL,
                        PathShape.BOARD_COLUMN,
                        null,
                        true),
                new RouteCase(
                        "DELETE .../tasks/{taskId}",
                        HttpMethod.DELETE,
                        TASK_URL,
                        PathShape.BOARD_COLUMN_TASK,
                        null,
                        true),
                new RouteCase(
                        "PUT .../tasks/{taskId}",
                        HttpMethod.PUT,
                        TASK_URL,
                        PathShape.BOARD_COLUMN_TASK,
                        UPDATE_TASK_BODY,
                        true),
                new RouteCase(
                        "POST .../tasks/{taskId}/subtasks",
                        HttpMethod.POST,
                        SUBTASKS_URL,
                        PathShape.BOARD_COLUMN_TASK,
                        SAVE_SUBTASK_BODY,
                        true),
                // SubtaskController -- 3
                new RouteCase(
                        "GET .../tasks/{taskId}/subtasks",
                        HttpMethod.GET,
                        SUBTASKS_URL,
                        PathShape.BOARD_COLUMN_TASK,
                        null,
                        true),
                new RouteCase(
                        "DELETE .../subtasks/{subtaskId}",
                        HttpMethod.DELETE,
                        SUBTASK_URL,
                        PathShape.BOARD_COLUMN_TASK_SUBTASK,
                        null,
                        true),
                new RouteCase(
                        "PUT .../subtasks/{subtaskId}",
                        HttpMethod.PUT,
                        SUBTASK_URL,
                        PathShape.BOARD_COLUMN_TASK_SUBTASK,
                        UPDATE_SUBTASK_BODY,
                        true),
                // UserController -- 2 (never cross-user applicable -- see class Javadoc)
                new RouteCase(
                        "GET /users/me/theme",
                        HttpMethod.GET,
                        USER_THEME_URL,
                        PathShape.NONE,
                        null,
                        false),
                new RouteCase(
                        "PUT /users/me/theme",
                        HttpMethod.PUT,
                        USER_THEME_URL,
                        PathShape.NONE,
                        UPDATE_THEME_BODY,
                        false),
                // TaskMoveController -- 1
                new RouteCase(
                        "PATCH /tasks/{taskId}/move",
                        HttpMethod.PATCH,
                        TASK_MOVE_URL,
                        PathShape.TASK_ONLY,
                        MOVE_TASK_BODY,
                        true),
                // ActivityController -- 1
                new RouteCase(
                        "GET /boards/{boardId}/activity",
                        HttpMethod.GET,
                        BOARD_ACTIVITY_URL,
                        PathShape.BOARD,
                        null,
                        true));
    }

    private static List<RouteCase> crossUserApplicableRoutes() {
        return routeTable().stream().filter(RouteCase::crossUserApplicable).toList();
    }

    private MockHttpServletRequestBuilder buildRequest(RouteCase route, FixtureIds ids)
            throws Exception {
        var pathVars = resolvePathVars(route.pathShape(), ids);
        var builder = request(route.method(), route.pathTemplate(), pathVars);
        if (route.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(route.body()));
        }
        return builder;
    }

    @Nested
    class NoSessionSweep {
        /**
         * Every protected route, requested with no session cookie, is rejected at
         * ProblemDetailAuthenticationEntryPoint before reaching a controller.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.vrudenko.kanban_board.security.AuthorizationGatingTest#routeTable")
        void shouldReturnUnauthorized_whenNoSessionIsPresent(RouteCase route) throws Exception {
            // arrange & act & assert
            mockMvc.perform(buildRequest(route, DUMMY_FIXTURE_IDS))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Nested
    class CrossUserSweep {
        /**
         * Every route addressing a specific resource, requested by getForeignUser()
         * against getOwningUser()'s resources, returns 403.
         *
         * The foreign user owns its own board and column, so a 403 proves ownership is enforced,
         * not merely an empty result set. Authenticated via .with(user(foreignUserId)), as
         * the controller/*ControllerTest classes do.
         */
        @ParameterizedTest(name = "{0}")
        @MethodSource(
                "com.vrudenko.kanban_board.security.AuthorizationGatingTest#crossUserApplicableRoutes")
        void shouldReturnForbidden_whenForeignUserAccessesOwningUsersResource(RouteCase route)
                throws Exception {
            // arrange
            var foreignUserId = getForeignUser().getId();
            var ids =
                    new FixtureIds(
                            mockPopulatedBoard.getId(),
                            mockPopulatedColumn.getId(),
                            mockPopulatedTask.getId(),
                            mockSubtasks.getFirst().getId());

            // act
            var result =
                    mockMvc.perform(buildRequest(route, ids).with(user(foreignUserId))).andReturn();

            // assert: 403, and the response body never leaks the protected resource's own data
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.FORBIDDEN.value());
            var body = result.getResponse().getContentAsString();
            Assertions.assertThat(body).contains("\"code\":\"ACCESS_DENIED\"");
            Assertions.assertThat(body).doesNotContain(mockPopulatedBoard.getName());
        }
    }

    @Nested
    class ScopedToCaller {
        // The four routes with no meaningful cross-user case are scoped to the caller by
        // construction: the foreign user sees only its own data, never the owning user's.

        @Test
        void shouldReturnOnlyForeignUsersOwnBoards_whenForeignUserListsBoards() throws Exception {
            // arrange
            var foreignUserId = getForeignUser().getId();

            // act
            var result =
                    mockMvc.perform(get(BOARDS_URL).with(user(foreignUserId)))
                            .andExpect(status().isOk())
                            .andReturn();

            // assert: the owning user's populated board never appears in the foreign user's list
            var boards =
                    List.of(
                            objectMapper.readValue(
                                    result.getResponse().getContentAsString(),
                                    BoardResponseDTO[].class));
            Assertions.assertThat(boards)
                    .extracting(BoardResponseDTO::getId)
                    .doesNotContain(mockPopulatedBoard.getId());
            Assertions.assertThat(boards)
                    .extracting(BoardResponseDTO::getId)
                    .contains(getForeignUserBoard().getId());
        }

        @Test
        void shouldCreateBoardOwnedByCaller_whenForeignUserCreatesBoard() throws Exception {
            // arrange
            var foreignUserId = getForeignUser().getId();

            // act
            var result =
                    mockMvc.perform(
                                    post(BOARDS_URL)
                                            .with(user(foreignUserId))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    objectMapper.writeValueAsString(
                                                            SAVE_BOARD_BODY)))
                            .andReturn();

            // assert: succeeds -- creation is inherently scoped to whoever authenticated, there is
            // no other user's resource being addressed here to leak or protect
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CREATED.value());
        }

        @Test
        void shouldReturnForeignUsersOwnTheme_whenForeignUserReadsTheme() throws Exception {
            // arrange
            var foreignUserId = getForeignUser().getId();

            // act
            var result =
                    mockMvc.perform(get(USER_THEME_URL).with(user(foreignUserId)))
                            .andExpect(status().isOk())
                            .andReturn();

            // assert: the response is the foreign user's own theme, resolved entirely from the
            // session's identity -- there is no path/body id another user's session could ever put
            // there
            var response =
                    objectMapper.readValue(
                            result.getResponse().getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(response.getId()).isEqualTo(foreignUserId);
        }

        @Test
        void shouldUpdateOnlyForeignUsersOwnTheme_whenForeignUserUpdatesTheme() throws Exception {
            // arrange
            var foreignUserId = getForeignUser().getId();

            // act
            var result =
                    mockMvc.perform(
                                    put(USER_THEME_URL)
                                            .with(user(foreignUserId))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    objectMapper.writeValueAsString(
                                                            UPDATE_THEME_BODY)))
                            .andReturn();

            // assert: succeeds, and updates the CALLER's theme, not the owning user's -- there is
            // no id in this request that could address anyone else's row
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.OK.value());
            var response =
                    objectMapper.readValue(
                            result.getResponse().getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(response.getId()).isEqualTo(foreignUserId);
        }
    }

    @Nested
    class PermitAllExclusions {
        // /signin and /signup are the two deliberately excluded permitAll() routes; proven
        // reachable without a session here so a silent regression would be caught.

        @Test
        void shouldBeReachableWithoutSession_whenPostingToSignin() throws Exception {
            // arrange: a well-shaped nonexistent-credential body; the point is that the request
            // reaches AuthenticationController at all, not that it succeeds
            var body =
                    SigninRequestDTO.builder()
                            .email(generateValidEmail())
                            .password(generateValidPassword())
                            .build();

            // act
            var result =
                    mockMvc.perform(
                                    post(ApiPaths.SIGNIN)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(body)))
                            .andReturn();

            // assert: rejected as bad credentials (401, BAD_CREDENTIALS), never the entry point's
            // UNAUTHENTICATED code -- proving the filter chain let the request all the way through
            // to AuthenticationController instead of blocking it at the gate
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(result.getResponse().getContentAsString())
                    .contains("\"code\":\"BAD_CREDENTIALS\"");
        }

        @Test
        void shouldBeReachableWithoutSession_whenPostingToSignup() throws Exception {
            // arrange
            var body =
                    SignupRequestDTO.builder()
                            .email(generateValidEmail())
                            .password(generateValidPassword())
                            .displayName(
                                    dataFactory.getRandomWord(
                                            ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                            .build();

            // act
            var result =
                    mockMvc.perform(
                                    post(ApiPaths.SIGNUP)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(body)))
                            .andReturn();

            // assert: a genuinely new signup succeeds (201), proving the route was never blocked at
            // the security filter chain
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CREATED.value());
        }
    }

    @Nested
    class Completeness {
        // Closes the "a future route ships with no table row" failure mode: every handler under
        // com.vrudenko.kanban_board.controller must have a routeTable() row.
        //
        // The seven domain controllers all carry class-level @PreAuthorize("isAuthenticated()").
        // AuthenticationController's two permitAll() routes live in the security package, which
        // the package filter already excludes (as it does SpringDoc's controllers): a deliberately
        // simpler equivalent of subtracting the permitAll() routes.

        private record DiscoveredRoute(HttpMethod httpMethod, String pattern) {}

        private Set<DiscoveredRoute> discoverProtectedRoutes() {
            return requestMappingHandlerMapping.getHandlerMethods().entrySet().stream()
                    .filter(
                            entry ->
                                    entry.getValue()
                                            .getBeanType()
                                            .getPackageName()
                                            .equals("com.vrudenko.kanban_board.controller"))
                    .flatMap(
                            entry -> {
                                var info = entry.getKey();
                                var pathPatternsCondition = info.getPathPatternsCondition();
                                Set<String> patterns =
                                        pathPatternsCondition != null
                                                ? pathPatternsCondition.getPatternValues()
                                                : (info.getPatternsCondition() != null
                                                        ? info.getPatternsCondition().getPatterns()
                                                        : Set.of());
                                var methods = info.getMethodsCondition().getMethods();

                                return patterns.stream()
                                        .flatMap(
                                                pattern ->
                                                        methods.stream()
                                                                .map(
                                                                        m ->
                                                                                new DiscoveredRoute(
                                                                                        HttpMethod
                                                                                                .valueOf(
                                                                                                        m
                                                                                                                .name()),
                                                                                        pattern)));
                            })
                    .collect(Collectors.toSet());
        }

        @Test
        void shouldCoverEveryDiscoveredRoute_withNoUnmatchedMapping() {
            // arrange
            var discovered = discoverProtectedRoutes();
            var tableRoutes =
                    routeTable().stream()
                            .map(r -> new DiscoveredRoute(r.method(), r.pathTemplate()))
                            .collect(Collectors.toSet());

            // assert: guard against a vacuous pass: the scan must find at least as many routes (22)
            // as the table holds
            Assertions.assertThat(discovered).isNotEmpty();
            Assertions.assertThat(discovered.size()).isGreaterThanOrEqualTo(22);

            // assert: every discovered mapping has a matching row in routeTable() -- a set
            // comparison, not a count, so a swap (one route added, a different one removed) cannot
            // pass by coincidence. The failure message names the unmatched mapping directly.
            var unmatched = discovered.stream().filter(d -> !tableRoutes.contains(d)).toList();
            Assertions.assertThat(unmatched)
                    .as(
                            "Routes discovered via RequestMappingHandlerMapping with no matching row"
                                    + " in AuthorizationGatingTest.routeTable(): %s",
                            unmatched)
                    .isEmpty();
        }
    }
}

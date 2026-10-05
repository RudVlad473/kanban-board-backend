package com.vrudenko.kanban_board.security;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.vrudenko.kanban_board.config.RandFlakeGenerator;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.UpdateSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adversarial payload coverage for parameter binding and validation boundaries.
 *
 * <p>One nested group per category: {@link SqlInjection}, {@link StoredXss}, {@link
 * OversizedBoundary}, {@link MalformedPathVariable}.
 *
 * <p>Runs through real {@link MockMvc} into the Testcontainers-backed PostgreSQL ({@code
 * docs/CODE_STYLE.md} rule 4): a mocked repository would prove nothing about parameter binding.
 *
 * <p>Why this is the way it is: it authenticates through {@link
 * AbstractAppMockMvcTest#signinCookie()} once per test and replays the cookie, not through {@code
 * .with(user(userId))}. That shortcut establishes a new HTTP session on every call, which trips
 * {@code MAX_CONCURRENT_SESSIONS = 2} on the third call for one principal, because {@code
 * SessionManagementFilter} holds its own DSL-composed, in-memory-registry-backed {@code
 * SessionAuthenticationStrategy}, a different instance from the {@code
 * sessionAuthenticationStrategy} bean the real signin path invokes. A real signin establishes one
 * session, so replaying its cookie never hits the ceiling; the interaction is invisible in
 * production, where signin pre-establishes its session before the security context is saved.
 *
 * <p>Prohibition: if any case fails, investigate the binding assumption, never add input
 * sanitization to production code. There is no raw or concatenated SQL in {@code src/main}; this
 * class exists to prove that stays true.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class InjectionAttemptTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Autowired private BoardService boardService;

    private static final String BOARD_COLUMNS_URL =
            ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS;
    private static final String COLUMN_URL =
            ApiPaths.BOARDS + ApiPaths.BOARD_ID + ApiPaths.COLUMNS + ApiPaths.COLUMN_ID;
    private static final String TASKS_URL = COLUMN_URL + ApiPaths.TASKS;
    private static final String TASK_URL = TASKS_URL + ApiPaths.TASK_ID;
    private static final String SUBTASKS_URL = TASK_URL + ApiPaths.SUBTASKS;
    private static final String SUBTASK_URL = SUBTASKS_URL + ApiPaths.SUBTASK_ID;
    private static final String BOARD_URL = ApiPaths.BOARDS + ApiPaths.BOARD_ID;
    private static final String BOARD_FULL_URL = BOARD_URL + ApiPaths.FULL;

    // SQL-meta-character payloads. None fit BoardName's `@Pattern` (letters/digits/spaces only), so
    // the full round-trip proof runs against Column/Task/Subtask free-text fields, and Board name
    // gets its own rejection test.
    private static final String SQL_STATEMENT_TERMINATOR_PAYLOAD = "test'; DROP TABLE columns; --";
    private static final String SQL_COMMENT_TAUTOLOGY_PAYLOAD = "x' OR '1'='1' --";
    private static final String SQL_TABLE_DROP_PAYLOAD = "Robert'); DROP TABLE students;--";

    // Stored-script payload: proves it round-trips verbatim, never that it is sanitized.
    private static final String XSS_SCRIPT_PAYLOAD = "<script>alert('xss')</script>";

    // color's rejection is the counterpoint to the verbatim round-trip decision: these payloads are
    // rejected (400), never stored, because color's format is closed, not because of sanitization.
    private static final String XSS_ATTRIBUTE_BREAKOUT_PAYLOAD = "\" onload=\"alert(1)";

    private ColumnResponseDTO createColumn(Cookie cookie, String boardId, String name)
            throws Exception {
        var result =
                mockMvc.perform(
                                post(BOARD_COLUMNS_URL, boardId)
                                        .cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        SaveColumnRequestDTO.builder()
                                                                .name(name)
                                                                .build())))
                        .andReturn();

        Assertions.assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.CREATED.value());
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), ColumnResponseDTO.class);
    }

    private List<ColumnResponseDTO> listColumns(Cookie cookie, String boardId) throws Exception {
        var result =
                mockMvc.perform(get(BOARD_COLUMNS_URL, boardId).cookie(cookie))
                        .andExpect(status().isOk())
                        .andReturn();
        return List.of(
                objectMapper.readValue(
                        result.getResponse().getContentAsString(), ColumnResponseDTO[].class));
    }

    private TaskResponseDTO createTask(
            Cookie cookie, String boardId, String columnId, String title, String description)
            throws Exception {
        var result =
                mockMvc.perform(
                                post(COLUMN_URL, boardId, columnId)
                                        .cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        SaveTaskRequestDTO.builder()
                                                                .title(title)
                                                                .description(description)
                                                                .build())))
                        .andReturn();

        Assertions.assertThat(result.getResponse().getStatus())
                .isEqualTo(HttpStatus.CREATED.value());
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), TaskResponseDTO.class);
    }

    private List<TaskResponseDTO> listTasks(Cookie cookie, String boardId, String columnId)
            throws Exception {
        var result =
                mockMvc.perform(get(TASKS_URL, boardId, columnId).cookie(cookie))
                        .andExpect(status().isOk())
                        .andReturn();
        return List.of(
                objectMapper.readValue(
                        result.getResponse().getContentAsString(), TaskResponseDTO[].class));
    }

    /**
     * Round-trips a subtask title through {@code PUT .../subtasks/{subtaskId}} against a subtask
     * created through the service layer via {@link #createSubtask()}.
     *
     * <p>Both this route and the creation route flow through {@code SubtaskRepository.save}, so the
     * persistence/binding guarantee under test is the same either way.
     */
    private SubtaskResponseDTO updateSubtaskTitle(
            Cookie cookie,
            String boardId,
            String columnId,
            String taskId,
            String subtaskId,
            Long version,
            String title)
            throws Exception {
        var result =
                mockMvc.perform(
                                put(SUBTASK_URL, boardId, columnId, taskId, subtaskId)
                                        .cookie(cookie)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                objectMapper.writeValueAsString(
                                                        UpdateSubtaskRequestDTO.builder()
                                                                .title(title)
                                                                .version(version)
                                                                .build())))
                        .andReturn();

        Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(HttpStatus.OK.value());
        return objectMapper.readValue(
                result.getResponse().getContentAsString(), SubtaskResponseDTO.class);
    }

    private List<SubtaskResponseDTO> listSubtasks(
            Cookie cookie, String boardId, String columnId, String taskId) throws Exception {
        var result =
                mockMvc.perform(get(SUBTASKS_URL, boardId, columnId, taskId).cookie(cookie))
                        .andExpect(status().isOk())
                        .andReturn();
        return List.of(
                objectMapper.readValue(
                        result.getResponse().getContentAsString(), SubtaskResponseDTO[].class));
    }

    @Nested
    class SqlInjection {

        /**
         * The full four-step proof against Column name, the least-restricted free-text field (no
         * {@code @Pattern}, unlike Board name).
         *
         * <p>(1) submit the payload, (2) read the created resource back byte-for-byte, (3) assert
         * the sibling fixture columns from {@code setup()} still exist, (4) perform a further
         * normal create-and-read, proving the table itself survived, not merely this row.
         */
        @Test
        void
                shouldRoundTripAsInertData_andSurviveTableIntegrity_whenColumnNameIsStatementTerminatingPayload()
                        throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var priorColumnIds =
                    listColumns(cookie, boardId).stream().map(ColumnResponseDTO::getId).toList();

            // act: (1) submit the payload as a column name
            var created = createColumn(cookie, boardId, SQL_STATEMENT_TERMINATOR_PAYLOAD);

            // assert: (2) read it back byte-for-byte
            var afterCreate = listColumns(cookie, boardId);
            var readBack =
                    afterCreate.stream()
                            .filter(column -> column.getId().equals(created.getId()))
                            .findFirst();
            Assertions.assertThat(readBack).isPresent();
            Assertions.assertThat(readBack.get().getName())
                    .isEqualTo(SQL_STATEMENT_TERMINATOR_PAYLOAD);

            // assert: (3) every sibling fixture column from setup() still exists
            Assertions.assertThat(afterCreate.stream().map(ColumnResponseDTO::getId).toList())
                    .containsAll(priorColumnIds);

            // act & assert: (4) the table survived -- a further normal create-and-read still works
            var followUp = createColumn(cookie, boardId, "Post-injection column");
            var afterFollowUp = listColumns(cookie, boardId);
            Assertions.assertThat(
                            afterFollowUp.stream()
                                    .anyMatch(c -> c.getId().equals(followUp.getId())))
                    .isTrue();
        }

        @Test
        void shouldRoundTripAsInertData_whenTaskTitleIsCommentTerminatedTautologyPayload()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();

            // act
            var created =
                    createTask(
                            cookie,
                            boardId,
                            columnId,
                            SQL_COMMENT_TAUTOLOGY_PAYLOAD,
                            "description");

            // assert: read back through a fresh GET, not just the create response
            var tasks = listTasks(cookie, boardId, columnId);
            var readBack =
                    tasks.stream().filter(t -> t.getId().equals(created.getId())).findFirst();
            Assertions.assertThat(readBack).isPresent();
            Assertions.assertThat(readBack.get().getTitle())
                    .isEqualTo(SQL_COMMENT_TAUTOLOGY_PAYLOAD);
        }

        @Test
        void shouldRoundTripAsInertData_whenTaskDescriptionIsTableDroppingPayload()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();

            // act
            var created =
                    createTask(cookie, boardId, columnId, "title-holder", SQL_TABLE_DROP_PAYLOAD);

            // assert
            var tasks = listTasks(cookie, boardId, columnId);
            var readBack =
                    tasks.stream().filter(t -> t.getId().equals(created.getId())).findFirst();
            Assertions.assertThat(readBack).isPresent();
            Assertions.assertThat(readBack.get().getDescription())
                    .isEqualTo(SQL_TABLE_DROP_PAYLOAD);
        }

        @Test
        void shouldRoundTripAsInertData_whenSubtaskTitleIsStatementTerminatingPayload()
                throws Exception {
            // arrange: the subtask is created through the service layer via createSubtask()
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var taskId = mockPopulatedTask.getId();
            var subtask = createSubtask();

            // act
            var updated =
                    updateSubtaskTitle(
                            cookie,
                            boardId,
                            columnId,
                            taskId,
                            subtask.getId(),
                            subtask.getVersion(),
                            SQL_STATEMENT_TERMINATOR_PAYLOAD);

            // assert: read back through a fresh GET
            Assertions.assertThat(updated.getTitle()).isEqualTo(SQL_STATEMENT_TERMINATOR_PAYLOAD);
            var subtasks = listSubtasks(cookie, boardId, columnId, taskId);
            var readBack =
                    subtasks.stream().filter(s -> s.getId().equals(subtask.getId())).findFirst();
            Assertions.assertThat(readBack).isPresent();
            Assertions.assertThat(readBack.get().getTitle())
                    .isEqualTo(SQL_STATEMENT_TERMINATOR_PAYLOAD);
        }

        /**
         * Board name's {@code @Pattern} (letters, digits, spaces only) rejects every classic
         * SQL-meta-character payload before JPA, with a clean 400, never a 500.
         */
        @Test
        void shouldReturnCleanValidationError_whenBoardNameIsStatementTerminatingPayload()
                throws Exception {
            // arrange
            var cookie = signinCookie();

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveBoardRequestDTO.builder()
                                                            .name(SQL_STATEMENT_TERMINATOR_PAYLOAD)
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        /**
         * A punctuation-free, SQL-keyword-bearing board name that satisfies {@code @BoardName}'s
         * whitelist still round-trips as inert text, so parameter binding holds independently of
         * the whitelist.
         */
        @Test
        void shouldRoundTripAsInertData_whenBoardNameContainsSqlKeywordsWithoutMetaCharacters()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var payload = "DROP TABLE boards OR 1 1";

            // act
            var result =
                    mockMvc.perform(
                                    post(ApiPaths.BOARDS)
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    objectMapper.writeValueAsString(
                                                            SaveBoardRequestDTO.builder()
                                                                    .name(payload)
                                                                    .build())))
                            .andReturn();
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.CREATED.value());
            var created =
                    objectMapper.readValue(
                            result.getResponse().getContentAsString(), BoardResponseDTO.class);

            // assert: read back through a fresh GET /boards
            var boards =
                    objectMapper.readValue(
                            mockMvc.perform(get(ApiPaths.BOARDS).cookie(cookie))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString(),
                            BoardResponseDTO[].class);
            var readBack =
                    List.of(boards).stream()
                            .filter(b -> b.getId().equals(created.getId()))
                            .findFirst();
            Assertions.assertThat(readBack).isPresent();
            Assertions.assertThat(readBack.get().getName()).isEqualTo(payload);
        }
    }

    @Nested
    class StoredXss {

        // This group claims only that a stored script/HTML payload round-trips verbatim: nothing
        // server-side chokes on, alters or executes it.
        //
        // Sanitizing for safe HTML rendering is out of scope by decision: this is a JSON API, and
        // escaping for display is the consuming frontend's responsibility. No test here asserts
        // the payload is escaped or stripped.

        @Test
        void shouldRoundTripVerbatim_whenColumnNameIsScriptPayload() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();

            // act
            var created = createColumn(cookie, boardId, XSS_SCRIPT_PAYLOAD);

            // assert
            var columns = listColumns(cookie, boardId);
            var readBack =
                    columns.stream().filter(c -> c.getId().equals(created.getId())).findFirst();
            Assertions.assertThat(readBack).isPresent();
            Assertions.assertThat(readBack.get().getName()).isEqualTo(XSS_SCRIPT_PAYLOAD);
        }

        @Test
        void shouldRoundTripVerbatim_whenTaskDescriptionIsScriptPayload() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();

            // act
            var created =
                    createTask(cookie, boardId, columnId, "xss-title-holder", XSS_SCRIPT_PAYLOAD);

            // assert
            var tasks = listTasks(cookie, boardId, columnId);
            var readBack =
                    tasks.stream().filter(t -> t.getId().equals(created.getId())).findFirst();
            Assertions.assertThat(readBack).isPresent();
            Assertions.assertThat(readBack.get().getDescription()).isEqualTo(XSS_SCRIPT_PAYLOAD);
        }

        @Test
        void shouldRoundTripVerbatim_whenSubtaskTitleIsScriptPayload() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var taskId = mockPopulatedTask.getId();
            var subtask = createSubtask();

            // act
            var updated =
                    updateSubtaskTitle(
                            cookie,
                            boardId,
                            columnId,
                            taskId,
                            subtask.getId(),
                            subtask.getVersion(),
                            XSS_SCRIPT_PAYLOAD);

            // assert
            Assertions.assertThat(updated.getTitle()).isEqualTo(XSS_SCRIPT_PAYLOAD);
        }

        /**
         * Board name's {@code @Pattern} whitelist blocks {@code <}/{@code >} as it blocks SQL
         * meta-characters: a clean 400, never a 500.
         */
        @Test
        void shouldReturnCleanValidationError_whenBoardNameIsScriptPayload() throws Exception {
            // arrange
            var cookie = signinCookie();

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveBoardRequestDTO.builder()
                                                            .name(XSS_SCRIPT_PAYLOAD)
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        /**
         * {@code color} has a closed format ({@code #RRGGBB}), so a script/HTML payload is rejected
         * at the DTO boundary instead of round-tripped verbatim.
         *
         * <p>Rejection is proven on status and on absence from persistence, never on absence from
         * the response body: Jackson silently drops an unrecognized JSON key, so a body-only check
         * would pass even if {@code color} did not exist. This anchors on the 400 field-error
         * envelope and a fresh GET.
         */
        @ParameterizedTest
        @ValueSource(strings = {XSS_SCRIPT_PAYLOAD, XSS_ATTRIBUTE_BREAKOUT_PAYLOAD})
        void shouldReturnBadRequestAndPersistNothing_whenColumnColorIsXssPayload(String payload)
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var priorColumnIds =
                    listColumns(cookie, boardId).stream().map(ColumnResponseDTO::getId).toList();
            var requestBody =
                    objectMapper.writeValueAsString(
                            Map.of("name", "xss-color-holder", "color", payload));

            // act
            var result =
                    mockMvc.perform(
                                    post(BOARD_COLUMNS_URL, boardId)
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(requestBody))
                            .andReturn();

            // assert: rejected with the field-error envelope
            Assertions.assertThat(result.getResponse().getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());

            // assert: no column was created (the set of column ids is unchanged), which is stronger
            // than checking the payload's absence from each column's color
            var afterAttempt = listColumns(cookie, boardId);
            Assertions.assertThat(afterAttempt.stream().map(ColumnResponseDTO::getId).toList())
                    .containsExactlyInAnyOrderElementsOf(priorColumnIds);
        }
    }

    @Nested
    class OversizedBoundary {

        // Every case derives its lengths from ValidationConstants and tests both directions of the
        // boundary, exactly MAX (succeeds) and MAX + 1 (400 field error).
        //
        // All seven controller/ classes carry class-level @Validated (enforced by
        // LayeringArchTest), so a @Valid @RequestBody failure throws
        // MethodArgumentNotValidException and returns VALIDATION_FAILED with a per-field "errors"
        // map everywhere. CONSTRAINT_VIOLATION is the code for a @PathVariable @NotBlank violation,
        // covered by MalformedPathVariable below and by
        // ErrorEnvelopeConsistencyTest#PathVariableConstraintEnvelope.

        private String lettersOfLength(int length) {
            return "A".repeat(length);
        }

        @Test
        void shouldAccept_whenBoardNameIsExactlyMaxLength() throws Exception {
            // arrange
            var cookie = signinCookie();
            var name = lettersOfLength(ValidationConstants.MAX_BOARD_NAME_LENGTH);

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
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldRejectWithValidationFailed_whenBoardNameExceedsMaxLengthByOne()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var name = lettersOfLength(ValidationConstants.MAX_BOARD_NAME_LENGTH + 1);

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
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        @Test
        void shouldAccept_whenColumnNameIsExactlyMaxLength() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var name = lettersOfLength(ValidationConstants.MAX_COLUMN_NAME_LENGTH);

            // act & assert
            mockMvc.perform(
                            post(BOARD_COLUMNS_URL, boardId)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveColumnRequestDTO.builder()
                                                            .name(name)
                                                            .build())))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldRejectWithValidationFailed_whenColumnNameExceedsMaxLengthByOne()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var name = lettersOfLength(ValidationConstants.MAX_COLUMN_NAME_LENGTH + 1);

            // act & assert
            mockMvc.perform(
                            post(BOARD_COLUMNS_URL, boardId)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveColumnRequestDTO.builder()
                                                            .name(name)
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        @Test
        void shouldAccept_whenTaskTitleIsExactlyMaxLength() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var title = lettersOfLength(ValidationConstants.MAX_TASK_TITLE_LENGTH);

            // act & assert
            mockMvc.perform(
                            post(COLUMN_URL, boardId, columnId)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveTaskRequestDTO.builder()
                                                            .title(title)
                                                            .description("boundary test")
                                                            .build())))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldRejectWithValidationFailed_whenTaskTitleExceedsMaxLengthByOne()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var title = lettersOfLength(ValidationConstants.MAX_TASK_TITLE_LENGTH + 1);

            // act & assert
            mockMvc.perform(
                            post(COLUMN_URL, boardId, columnId)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveTaskRequestDTO.builder()
                                                            .title(title)
                                                            .description("boundary test")
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.title").exists());
        }

        @Test
        void shouldAccept_whenTaskDescriptionIsExactlyMaxLength() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var description = lettersOfLength(ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH);

            // act & assert
            mockMvc.perform(
                            post(COLUMN_URL, boardId, columnId)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveTaskRequestDTO.builder()
                                                            .title("boundary-title")
                                                            .description(description)
                                                            .build())))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldRejectWithValidationFailed_whenTaskDescriptionExceedsMaxLengthByOne()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var description = lettersOfLength(ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH + 1);

            // act & assert
            mockMvc.perform(
                            post(COLUMN_URL, boardId, columnId)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    SaveTaskRequestDTO.builder()
                                                            .title("boundary-title")
                                                            .description(description)
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.description").exists());
        }

        @Test
        void shouldAccept_whenSubtaskTitleIsExactlyMaxLength() throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var taskId = mockPopulatedTask.getId();
            var subtask = createSubtask();
            var title = lettersOfLength(ValidationConstants.MAX_SUBTASK_TITLE_LENGTH);

            // act & assert
            mockMvc.perform(
                            put(SUBTASK_URL, boardId, columnId, taskId, subtask.getId())
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    UpdateSubtaskRequestDTO.builder()
                                                            .title(title)
                                                            .version(subtask.getVersion())
                                                            .build())))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldRejectWithValidationFailed_whenSubtaskTitleExceedsMaxLengthByOne()
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var taskId = mockPopulatedTask.getId();
            var subtask = createSubtask();
            var title = lettersOfLength(ValidationConstants.MAX_SUBTASK_TITLE_LENGTH + 1);

            // act & assert
            mockMvc.perform(
                            put(SUBTASK_URL, boardId, columnId, taskId, subtask.getId())
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    UpdateSubtaskRequestDTO.builder()
                                                            .title(title)
                                                            .version(subtask.getVersion())
                                                            .build())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.title").exists());
        }
    }

    @Nested
    class MalformedPathVariable {

        // Path-traversal, SQL-meta-character and non-ULID ids against every resource type: each
        // case asserts 400 or 404, never a 5xx.
        //
        // Neither status is pinned: the routing layer and the service layer legitimately produce
        // different codes for different malformed shapes. URI-template substitution
        // (mockMvc.perform(get(template, malformedId))) is used rather than concatenation, so
        // "../.." is one opaque, percent-encoded path segment, as a JSON API client would send it,
        // never resolved as relative navigation.

        @ParameterizedTest
        @ValueSource(strings = {"../../../etc/passwd", "1' OR '1'='1", "not-a-real-ulid-value"})
        void shouldReturnBadRequestOrNotFound_whenBoardIdIsMalformed(String malformedBoardId)
                throws Exception {
            // arrange
            var cookie = signinCookie();

            // act
            var result =
                    mockMvc.perform(get(BOARD_FULL_URL, malformedBoardId).cookie(cookie))
                            .andReturn();

            // assert
            var status = result.getResponse().getStatus();
            Assertions.assertThat(status)
                    .isIn(HttpStatus.BAD_REQUEST.value(), HttpStatus.NOT_FOUND.value());
        }

        @ParameterizedTest
        @ValueSource(strings = {"../../../etc/passwd", "1' OR '1'='1", "not-a-real-ulid-value"})
        void shouldReturnBadRequestOrNotFound_whenColumnIdIsMalformed(String malformedColumnId)
                throws Exception {
            // arrange: boardId is a genuine, owned fixture -- only columnId is malformed
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();

            // act
            var result =
                    mockMvc.perform(delete(COLUMN_URL, boardId, malformedColumnId).cookie(cookie))
                            .andReturn();

            // assert
            var status = result.getResponse().getStatus();
            Assertions.assertThat(status)
                    .isIn(HttpStatus.BAD_REQUEST.value(), HttpStatus.NOT_FOUND.value());
        }

        @ParameterizedTest
        @ValueSource(strings = {"../../../etc/passwd", "1' OR '1'='1", "not-a-real-ulid-value"})
        void shouldReturnBadRequestOrNotFound_whenTaskIdIsMalformed(String malformedTaskId)
                throws Exception {
            // arrange: boardId/columnId are genuine, owned fixtures -- only taskId is malformed
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();

            // act
            var result =
                    mockMvc.perform(
                                    delete(TASK_URL, boardId, columnId, malformedTaskId)
                                            .cookie(cookie))
                            .andReturn();

            // assert
            var status = result.getResponse().getStatus();
            Assertions.assertThat(status)
                    .isIn(HttpStatus.BAD_REQUEST.value(), HttpStatus.NOT_FOUND.value());
        }

        @ParameterizedTest
        @ValueSource(strings = {"../../../etc/passwd", "1' OR '1'='1", "not-a-real-ulid-value"})
        void shouldReturnBadRequestOrNotFound_whenSubtaskIdIsMalformed(String malformedSubtaskId)
                throws Exception {
            // arrange: boardId/columnId/taskId are genuine, owned fixtures -- only subtaskId is
            // malformed
            var cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var columnId = mockPopulatedColumn.getId();
            var taskId = mockPopulatedTask.getId();

            // act
            var result =
                    mockMvc.perform(
                                    delete(
                                                    SUBTASK_URL,
                                                    boardId,
                                                    columnId,
                                                    taskId,
                                                    malformedSubtaskId)
                                            .cookie(cookie))
                            .andReturn();

            // assert
            var status = result.getResponse().getStatus();
            Assertions.assertThat(status)
                    .isIn(HttpStatus.BAD_REQUEST.value(), HttpStatus.NOT_FOUND.value());
        }
    }

    @Nested
    class MalformedBoardId {

        // Board-id cases reuse the XSS payload constants and prove rejection on status, the
        // VALIDATION_FAILED envelope and persistence, never on the response body alone.
        //
        // Jackson silently drops an unrecognised JSON key, so a body-only proof would pass even if
        // the id field were never wired (same reasoning as StoredXss's color case). Every length
        // case derives from ValidationConstants.MAX_BOARD_ID_LENGTH and tests both sides of the
        // boundary.

        private String boardIdOfLength(int length) {
            return "1".repeat(length);
        }

        @ParameterizedTest
        @ValueSource(strings = {XSS_SCRIPT_PAYLOAD, XSS_ATTRIBUTE_BREAKOUT_PAYLOAD})
        void shouldReturnBadRequestAndPersistNothing_whenBoardIdIsXssPayload(String payload)
                throws Exception {
            // arrange
            var cookie = signinCookie();
            var priorBoardIds =
                    boardService.findAllByUserId(getOwningUser().getId()).stream()
                            .map(BoardResponseDTO::getId)
                            .toList();

            // act & assert: rejected with the field-error envelope
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    Map.of(
                                                            "name",
                                                            "xss id holder",
                                                            "id",
                                                            payload))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.id").exists());

            // assert: no board was created at all
            var afterAttempt =
                    boardService.findAllByUserId(getOwningUser().getId()).stream()
                            .map(BoardResponseDTO::getId)
                            .toList();
            Assertions.assertThat(afterAttempt).containsExactlyInAnyOrderElementsOf(priorBoardIds);
        }

        /**
         * The generator emits lowercase base36 only, so an uppercase id is one this application
         * never issues and must be rejected though otherwise well-formed.
         */
        @Test
        void shouldRejectWithValidationFailed_whenBoardIdIsUppercase() throws Exception {
            // arrange: a real generator output, uppercased -- proves the charset check, not the
            // length check
            var cookie = signinCookie();
            var uppercaseId = new RandFlakeGenerator().generateRandflake().toUpperCase(Locale.ROOT);

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    Map.of(
                                                            "name",
                                                            "uppercase id holder",
                                                            "id",
                                                            uppercaseId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.id").exists());
        }

        /**
         * The acceptance case at the bound stops this group passing against an implementation that
         * rejects every id.
         */
        @Test
        void shouldAccept_whenBoardIdIsExactlyMaxLength() throws Exception {
            // arrange
            var cookie = signinCookie();
            var id = boardIdOfLength(ValidationConstants.MAX_BOARD_ID_LENGTH);

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    Map.of(
                                                            "name",
                                                            "max length id holder",
                                                            "id",
                                                            id))))
                    .andExpect(status().isCreated());
        }

        @Test
        void shouldRejectWithValidationFailed_whenBoardIdExceedsMaxLengthByOne() throws Exception {
            // arrange
            var cookie = signinCookie();
            var id = boardIdOfLength(ValidationConstants.MAX_BOARD_ID_LENGTH + 1);

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    Map.of(
                                                            "name",
                                                            "over length id holder",
                                                            "id",
                                                            id))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.id").exists());
        }

        @Test
        void shouldRejectWithValidationFailed_whenBoardIdContainsPunctuation() throws Exception {
            // arrange: well-formed length, one embedded hyphen -- proves the charset check
            // independently of the length check above
            var cookie = signinCookie();
            var id = boardIdOfLength(ValidationConstants.MAX_BOARD_ID_LENGTH - 1) + "-";

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    Map.of(
                                                            "name",
                                                            "punctuation id holder",
                                                            "id",
                                                            id))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.id").exists());
        }

        @Test
        void shouldRejectWithValidationFailed_whenBoardIdContainsWhitespace() throws Exception {
            // arrange: well-formed length, one embedded space
            var cookie = signinCookie();
            var id = boardIdOfLength(ValidationConstants.MAX_BOARD_ID_LENGTH - 1) + " ";

            // act & assert
            mockMvc.perform(
                            post(ApiPaths.BOARDS)
                                    .cookie(cookie)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            objectMapper.writeValueAsString(
                                                    Map.of(
                                                            "name",
                                                            "whitespace id holder",
                                                            "id",
                                                            id))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors.id").exists());
        }
    }
}

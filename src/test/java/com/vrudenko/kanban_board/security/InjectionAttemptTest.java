package com.vrudenko.kanban_board.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.util.List;
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

/**
 * D-16/D-17/D-18: adversarial payload coverage proving the guarantees this codebase's JPA/Hibernate
 * parameter binding and Jakarta Validation boundaries are believed to hold, rather than merely
 * assumed to hold. Structured as one {@code @Nested} group per D-16 payload category: {@link
 * SqlInjection}, {@link StoredXss}, {@link OversizedBoundary}, {@link MalformedPathVariable}.
 *
 * <p>Runs through real {@link MockMvc} into the real Testcontainers-backed PostgreSQL instance
 * ({@code docs/CODE_STYLE.md} rule 4) -- a mocked repository would prove nothing about parameter
 * binding, which is the entire property under test here. Authenticates via {@link
 * AbstractAppMockMvcTest#signinCookie()}, calling it once per test and replaying the returned
 * cookie on every subsequent request in that method, rather than the {@code .with(user(userId))}
 * shortcut {@code controller/*ControllerTest} classes use: several cases here make three or more
 * authenticated requests per test method, and {@code .with(user(userId))} establishes a brand-new
 * HTTP session on every call (via {@code HttpSessionSecurityContextRepository}) -- which trips
 * {@code SecurityConfiguration}'s own {@code MAX_CONCURRENT_SESSIONS = 2} ceiling on the third call
 * for the same principal, since {@code SessionManagementFilter} treats each of those as a fresh
 * login. A real signin only establishes one session, so replaying its cookie never hits that
 * ceiling -- this is a genuine, non-obvious interaction discovered while writing this class
 * (invisible in production, where the one real signin path always pre-establishes its session
 * before the security context is ever saved), recorded in this plan's SUMMARY rather than fixed,
 * since nothing in {@code src/main} is wrong.
 *
 * <p><b>Prohibition, restated from the plan:</b> if any case in this class fails, the correct
 * response is to investigate the binding assumption, never to add input sanitization/escaping to
 * production code. There is no raw or concatenated SQL anywhere in {@code src/main}; this class
 * exists to prove that stays true, not to introduce a second layer of defence.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class InjectionAttemptTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

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

    // -- SQL-meta-character payloads (D-16, D-18). None of these fit BoardName's `@Pattern`
    // (letters/digits/spaces only, see SqlInjection's Board name cases below), so the full
    // round-trip proof runs against Column/Task/Subtask free-text fields instead -- Board name gets
    // its own dedicated rejection test proving the character whitelist blocks these cleanly.
    private static final String SQL_STATEMENT_TERMINATOR_PAYLOAD = "test'; DROP TABLE columns; --";
    private static final String SQL_COMMENT_TAUTOLOGY_PAYLOAD = "x' OR '1'='1' --";
    private static final String SQL_TABLE_DROP_PAYLOAD = "Robert'); DROP TABLE students;--";

    // D-16's XSS/stored-script group: proves the payload round-trips verbatim, never that it is
    // sanitized. See StoredXss's class Javadoc for the explicit scope statement.
    private static final String XSS_SCRIPT_PAYLOAD = "<script>alert('xss')</script>";

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
     * {@code TaskController.addSubtaskByTaskId} binds its DTO without {@code @RequestBody} (filed:
     * {@code 2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md}), so a
     * JSON-bodied POST to the subtask-creation route does not populate {@code title} the way every
     * sibling creation endpoint does. Rather than work around or fix that separately-tracked defect
     * here, this class proves the subtask round-trip through {@code PUT .../subtasks/{subtaskId}}
     * (which DOES carry {@code @RequestBody}) against a subtask created through the service layer
     * via {@link #createSubtask()} -- the persistence/binding guarantee under test is identical
     * either way, since both routes ultimately flow through the same {@code
     * SubtaskRepository.save}.
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
         * D-18's full four-step proof, run against Column name -- the least-restricted free-text
         * field available (no {@code @Pattern}, unlike Board name). (1) submit the payload, (2)
         * read the created resource back and assert byte-for-byte equality, (3) assert the sibling
         * fixture columns from {@code setup()} still exist, (4) perform one further normal
         * create-and-read against the same table, proving the table itself -- not merely this one
         * row -- survived.
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
            // arrange: created through the service layer (createSubtask(), AbstractAppTest) so this
            // case sidesteps the separately-tracked addSubtaskByTaskId @RequestBody defect entirely
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
         * Board name's own {@code @Pattern} (letters, digits, spaces only) rejects every classic
         * SQL-meta-character payload before it ever reaches JPA -- a defence-in-depth property
         * discovered while writing this class, not something introduced by it. This proves the
         * rejection is clean (400, never 500), matching D-16's "malformed input degrades cleanly"
         * guarantee for this field.
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
         * A punctuation-free, SQL-keyword-bearing board name (satisfies {@code @BoardName}'s
         * whitelist) still round-trips as inert literal text -- proving the parameter-binding
         * guarantee holds for Board name too, independent of the whitelist above.
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

        // D-16: this group's claim is only that a stored script/HTML payload round-trips verbatim
        // -- nothing server-side chokes on it, alters it, or executes it. Sanitizing for safe HTML
        // rendering is explicitly out of scope by decision: this is a JSON API, and escaping for
        // display is the consuming frontend's responsibility, not this backend's. No test in this
        // group asserts the payload is escaped/stripped -- only that it is preserved and handled
        // cleanly.

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
         * Board name's {@code @Pattern} whitelist blocks {@code <}/{@code >} the same way it blocks
         * SQL meta-characters -- proven here for symmetry with {@code SqlInjection}'s equivalent
         * Board name case, and to confirm the rejection is clean (400), never a 500.
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
    }

    @Nested
    class OversizedBoundary {

        // D-16: every case below derives its lengths from ValidationConstants rather than a
        // hard-coded number, and tests both directions of the boundary -- exactly MAX (must
        // succeed) and MAX + 1 (must be a 400 field error) -- proving the constraint sits exactly
        // where ValidationConstants says it does, not merely that some limit exists.
        //
        // Two of this application's seven controllers (BoardController, UserController,
        // ActivityController) carry a class-level @Validated; the other four (ColumnController,
        // TaskController, SubtaskController, TaskMoveController) do not. That asymmetry changes
        // WHICH exception Spring throws for the exact same kind of @Valid @RequestBody field
        // failure: @Validated-carrying controllers throw MethodArgumentNotValidException (this
        // plan's converged VALIDATION_FAILED code, with a per-field "errors" map), while the
        // others throw HandlerMethodValidationException (the pre-existing CONSTRAINT_VIOLATION
        // code, with no "errors" map) -- discovered empirically while writing this group, not
        // assumed. Both are still clean 400s, matching D-16's "clean rejection" claim; only the
        // envelope shape differs. Each case below asserts the code its own controller ACTUALLY
        // returns, and this finding is recorded in this plan's SUMMARY as a real, pre-existing
        // envelope inconsistency this plan's test-only scope does not fix (verification's "no
        // production code changed" constraint applies) -- not something this class introduces or
        // papers over.

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
            // arrange: BoardController carries @Validated -- MethodArgumentNotValidException,
            // VALIDATION_FAILED, per-field errors map
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

            // act & assert: POST /boards/{boardId}/columns is on BoardController (@Validated)
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
        void shouldRejectWithConstraintViolation_whenTaskTitleExceedsMaxLengthByOne()
                throws Exception {
            // arrange: this creation route is on ColumnController, which is NOT @Validated -- see
            // this group's class comment for why the code differs from Board/Column name above
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
                    .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));
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
        void shouldRejectWithConstraintViolation_whenTaskDescriptionExceedsMaxLengthByOne()
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
                    .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));
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
        void shouldRejectWithConstraintViolation_whenSubtaskTitleExceedsMaxLengthByOne()
                throws Exception {
            // arrange: SubtaskController is also not @Validated -- same envelope as Task above
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
                    .andExpect(jsonPath("$.code").value("CONSTRAINT_VIOLATION"));
        }
    }

    @Nested
    class MalformedPathVariable {

        // D-16: a path-traversal-shaped id, an id containing SQL meta-characters, and a plainly
        // non-ULID string, each exercised against at least one route per resource type. Every case
        // asserts the response is 400 or 404 -- never a 5xx. The actual observed status per case is
        // recorded in this plan's SUMMARY (Claude's discretion per CONTEXT.md: neither status is
        // pinned single-valued, since the routing layer and the service layer legitimately produce
        // different codes for different malformed shapes).
        //
        // URI-template variable substitution (mockMvc.perform(get(template, malformedId))) is used
        // throughout rather than raw string concatenation, so a "../.." payload is treated as a
        // single opaque path-segment value (percent-encoded by Spring's UriComponentsBuilder) --
        // exactly what a JSON API client sending that literal string as an id would produce -- and
        // never resolved as real relative navigation against this test's own request path.

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
}

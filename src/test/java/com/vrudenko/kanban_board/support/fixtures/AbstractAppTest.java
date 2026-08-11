package com.vrudenko.kanban_board.support.fixtures;

import java.util.Locale;
import java.util.stream.Stream;

import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.annotation.Password;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SaveSubtaskRequestDTO;
import com.vrudenko.kanban_board.dto.subtask_dto.SubtaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.user_dto.SignupRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.service.ColumnService;
import com.vrudenko.kanban_board.service.TaskService;
import com.vrudenko.kanban_board.service.UserService;
import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;
import com.vrudenko.kanban_board.support.listeners.RecordingActivityEventListener;

import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManagerFactory;
import lombok.Getter;
import org.apache.commons.lang3.RandomStringUtils;
import org.fluttercode.datafactory.impl.DataFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared fixture base for ~19 service/controller/E2E test classes. Inherits the one shared
 * PostgreSQL container from {@link AbstractPostgresContainerTest} (04.2, D-01) -- the schema its
 * subclasses see is built by Flyway V1-V4, the same migrations production runs, not by Hibernate.
 *
 * <p>Per-test isolation is {@link #cleanup()}'s plain {@code @AfterEach} deletion (D-02), never
 * test-managed {@code @Transactional} rollback. Rollback was considered and rejected on three
 * verified grounds: it never delivers {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * events, since a rolled-back transaction never commits; it corrupts {@link
 * #countQueries(Runnable)}'s {@code getPrepareStatementCount()} metric, since a shared persistence
 * context across {@code @BeforeEach} and the act keeps fixtures in the first-level cache and hides
 * {@code findById()} calls; and it gives {@code AbstractAppE2ETest}'s real, cross-thread HTTP
 * round-trips no isolation at all, since the test thread's transaction never spans the request.
 */
public abstract class AbstractAppTest extends AbstractPostgresContainerTest {
    private @Autowired UserService userService;
    private @Autowired BoardService boardService;
    private @Autowired ColumnService columnService;
    private @Autowired TaskService taskService;
    private @Autowired ActivityLogRepository activityLogRepository;

    private @Autowired RecordingActivityEventListener recordingActivityEventListener;

    private @Autowired EntityManagerFactory entityManagerFactory;

    protected final DataFactory dataFactory = new DataFactory();

    protected final int MOCK_COLUMNS_AMOUNT = 7;
    protected final int MOCK_TASKS_AMOUNT = 7;
    protected final int MOCK_SUBTASKS_AMOUNT = 7;

    // users
    @Getter private final String owningUserPassword = generateValidPassword();

    @Getter private UserResponseDTO owningUser;

    @Getter private final String noBoardsUserPassword = generateValidPassword();

    @Getter private UserResponseDTO noBoardsUser;

    @Getter private final String foreignUserPassword = generateValidPassword();

    @Getter private UserResponseDTO foreignUser;

    /**
     * A board owned by {@link #getForeignUser()}, not {@link #getOwningUser()}. Distinct from
     * {@link #getNoBoardsUser()}: the no-boards user owns nothing, so a cross-user test against it
     * only proves an empty account sees nothing. The foreign user owns a genuine board+column, so a
     * cross-user test targeting {@link #getForeignUserColumn()} proves a legitimate owner is still
     * refused someone else's resource (D-20).
     */
    @Getter private BoardResponseDTO foreignUserBoard;

    @Getter private ColumnResponseDTO foreignUserColumn;

    // boards
    protected ImmutableList<BoardResponseDTO> mockEmptyBoards = ImmutableList.of();
    protected BoardResponseDTO mockPopulatedBoard = BoardResponseDTO.builder().build();

    // columns
    protected ImmutableList<ColumnResponseDTO> mockColumns = ImmutableList.of();
    protected ColumnResponseDTO mockPopulatedColumn = ColumnResponseDTO.builder().build();

    // tasks
    protected ImmutableList<TaskResponseDTO> mockTasks = ImmutableList.of();
    protected TaskResponseDTO mockPopulatedTask = TaskResponseDTO.builder().build();

    // subtasks
    protected ImmutableList<SubtaskResponseDTO> mockSubtasks = ImmutableList.of();

    @BeforeEach
    protected void setup() {
        // user
        owningUser = createUser(owningUserPassword);
        noBoardsUser = createUser(noBoardsUserPassword);

        // board
        mockEmptyBoards =
                ImmutableList.copyOf(
                        Stream.of("Todo", "Done")
                                .map(
                                        (boardName) ->
                                                userService.addBoardByUserId(
                                                        getOwningUser().getId(),
                                                        SaveBoardRequestDTO.builder()
                                                                .name(boardName)
                                                                .build()))
                                .toList());
        mockPopulatedBoard =
                userService.addBoardByUserId(
                        getOwningUser().getId(),
                        SaveBoardRequestDTO.builder().name("In progress").build());

        // column
        mockColumns =
                ImmutableList.copyOf(
                        Stream.generate(
                                        () ->
                                                dataFactory.getRandomWord(
                                                        ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                .limit(MOCK_COLUMNS_AMOUNT)
                                .map(
                                        columnName ->
                                                boardService.addColumnByBoardId(
                                                        getOwningUser().getId(),
                                                        mockPopulatedBoard.getId(),
                                                        SaveColumnRequestDTO.builder()
                                                                .name(columnName)
                                                                .build()))
                                .toList());
        mockPopulatedColumn =
                columnService.save(
                        SaveColumnRequestDTO.builder()
                                .name(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_BOARD_NAME_LENGTH + 4))
                                .build(),
                        boardService.findById(getOwningUser().getId(), mockPopulatedBoard.getId()));

        // task
        mockTasks =
                ImmutableList.copyOf(
                        Stream.generate(() -> null)
                                .limit(MOCK_TASKS_AMOUNT)
                                .map((ignore) -> createTask())
                                .toList());
        mockPopulatedTask = createTask();

        // subtask
        mockSubtasks =
                ImmutableList.copyOf(
                        Stream.generate(() -> null)
                                .limit(MOCK_SUBTASKS_AMOUNT)
                                .map((ignore) -> createSubtask())
                                .toList());

        // foreign user (D-20) -- created last so it never shifts the insertion order
        // boardService.findAll()/columnService.findAll() etc. return for the owning user's
        // fixtures above, which several existing tests assert against positionally (e.g.
        // BoardServiceTest.testUpdateById_shouldUpdateBoard_whenBoardExists() takes
        // boardService.findAll().getFirst() and expects it to be an owningUser board).
        foreignUser = createUser(foreignUserPassword);
        foreignUserBoard = createBoardForUser(foreignUser.getId(), "Foreign board");
        foreignUserColumn =
                boardService.addColumnByBoardId(
                        foreignUser.getId(),
                        foreignUserBoard.getId(),
                        SaveColumnRequestDTO.builder()
                                .name(
                                        dataFactory.getRandomWord(
                                                ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                                .build());
    }

    /**
     * {@code activity_log} is the only table in the schema whose rows are unreachable from {@link
     * UserService#deleteAll()}'s cascade, because {@code V3__add_activity_log.sql} declares {@code
     * board_id}/{@code user_id} as plain {@code NOT NULL} columns with no foreign key -- a
     * deliberate entity-level design choice ({@link
     * com.vrudenko.kanban_board.entity.ActivityLogEntity}'s Javadoc), not an oversight to be fixed
     * with a migration. H2's per-context {@code create-drop} masked this gap (04.2, D-02a); a
     * long-lived container does not, so the second call below closes it explicitly. This hook, not
     * a test-managed {@code @Transactional} rollback, is this codebase's single isolation model
     * (D-02) -- a future author should extend this method for a new FK-less table, not add a second
     * isolation mechanism alongside it.
     *
     * <p>The third call clears {@link RecordingActivityEventListener}'s singleton {@code
     * CopyOnWriteArrayList}, shared across every test class in a JVM fork (S5E). Safe here because
     * {@link RecordingActivityEventListener#onActivityEvent} carries no {@code @Async} annotation
     * (confirmed by reading the class, contrasted with {@code KafkaEventPublisher.onActivityEvent},
     * which does) -- it runs synchronously on the committing thread immediately after commit, so
     * every event this test method's fixtures and body produced has already been recorded by the
     * time this {@code @AfterEach} hook runs; nothing queued on another thread can be lost by
     * clearing here. Every test that asserts on the recorder already clears it in its own arrange
     * block first, so this addition changes accumulation across the whole run, not what any
     * individual test observes.
     */
    @AfterEach
    void cleanup() {
        userService.deleteAll();
        activityLogRepository.deleteAll();
        recordingActivityEventListener.clear();
    }

    /**
     * Returns a password value guaranteed to satisfy {@link Password}: at least one lowercase
     * letter, one uppercase letter, one digit, and one special character, with total length between
     * {@link ValidationConstants#MIN_PASSWORD_LENGTH} and {@link
     * ValidationConstants#MAX_PASSWORD_LENGTH}. Fixture passwords must satisfy {@code @Password}
     * because {@link AbstractAppMockMvcTest#signinCookie()} posts them to the real {@code POST
     * /signin} route, which validates its request body as of D-06.
     */
    protected String generateValidPassword() {
        var base =
                dataFactory
                        .getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH)
                        .toLowerCase(Locale.ROOT);

        // "Aa1!" appends one uppercase letter, one digit, and one special character on top of
        // the lowercased base word -- satisfying every @Password character class regardless of
        // what dataFactory happened to generate.
        return base + "Aa1!";
    }

    /**
     * Returns an email value guaranteed to satisfy {@code @AppEmail}'s {@code @Email} format
     * constraint. Deliberately does NOT use {@code dataFactory.getEmailAddress()}: its word-based
     * local-part branch draws from the same small, dirty corpus already found (07.1-07 task 1) to
     * contain literal multi-word phrases lifted from running story text -- e.g. the single array
     * entry {@code "or maybe"} -- which {@code getEmailAddress()} then concatenates with a second
     * word with no separator, occasionally producing an email with an embedded space (e.g. {@code
     * "or maybedreams@ma1lbox.org"}). That fails {@code @Email}'s format check, but
     * {@code @AppEmail}'s {@code @ReportAsSingleViolation} collapses the failure into the composed
     * annotation's generic {@code "Email cannot be empty"} message regardless of which
     * sub-constraint actually failed -- a real, confusing bug in this codebase's own test fixtures,
     * not in {@code AuthenticationController} or the validation itself. Root-caused via a temporary
     * diagnostic on {@link AbstractAppMockMvcTest#signinCookie(String, String)} that captured the
     * exact malformed value from a live failure, and independently confirmed by decompiling {@code
     * datafactory-0.8.jar}'s {@code DefaultContentDataValues} constant pool.
     */
    protected String generateValidEmail() {
        return RandomStringUtils.randomAlphabetic(10).toLowerCase(Locale.ROOT) + "@example.com";
    }

    protected UserResponseDTO createUser() {
        return createUser(generateValidPassword());
    }

    protected UserResponseDTO createUser(String password) {
        return userService.save(
                SignupRequestDTO.builder()
                        .email(generateValidEmail())
                        .displayName(
                                dataFactory.getRandomWord(
                                        ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                        .password(password)
                        .build());
    }

    /**
     * Creates a board+column owned by an arbitrary user, for tests that need a fixture owned by
     * someone other than {@link #getOwningUser()} (e.g. cross-user ownership rejection tests).
     * There is no REST endpoint for creating a board directly (boards are only created via {@link
     * com.vrudenko.kanban_board.service.UserService#addBoardByUserId}), so this goes through the
     * service layer directly, same as the rest of this class's fixture setup.
     */
    protected ColumnResponseDTO createColumnForUser(
            String userId, String boardName, String columnName) {
        var board = createBoardForUser(userId, boardName);

        return boardService.addColumnByBoardId(
                userId, board.getId(), SaveColumnRequestDTO.builder().name(columnName).build());
    }

    /**
     * Creates a board owned by an arbitrary user, without adding a column. Sibling to {@link
     * #createColumnForUser(String, String, String)} for callers (e.g. {@link
     * #getForeignUserBoard()} fixture setup) that need the board reference itself, not just a
     * column within it.
     */
    protected BoardResponseDTO createBoardForUser(String userId, String boardName) {
        return userService.addBoardByUserId(
                userId, SaveBoardRequestDTO.builder().name(boardName).build());
    }

    protected TaskResponseDTO createTask() {
        return columnService.addTaskByColumnId(
                getOwningUser().getId(),
                mockPopulatedColumn.getId(),
                SaveTaskRequestDTO.builder()
                        .title(
                                dataFactory.getRandomWord(
                                        ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
                        .description(
                                dataFactory.getRandomText(
                                        ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                                        ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH))
                        .build());
    }

    /**
     * Returns the number of JDBC statements Hibernate prepared while running {@code action}. Uses
     * {@code getPrepareStatementCount()} rather than {@code getQueryExecutionCount()} because the
     * latter only counts HQL/JPQL queries, not {@code find()}-by-id lookups (which is what {@code
     * repository.findById()} compiles to).
     */
    protected long countQueries(Runnable action) {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        action.run();
        return statistics.getPrepareStatementCount();
    }

    protected SubtaskResponseDTO createSubtask() {
        return taskService.addSubtaskByTaskId(
                getOwningUser().getId(),
                mockPopulatedTask.getId(),
                SaveSubtaskRequestDTO.builder()
                        .title(
                                dataFactory.getRandomText(
                                        ValidationConstants.MIN_SUBTASK_TITLE_LENGTH + 1))
                        .build());
    }
}

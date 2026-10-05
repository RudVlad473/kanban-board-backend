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
 * Shared fixture base for the service, controller and E2E test classes, on the one PostgreSQL
 * container inherited from {@link AbstractPostgresContainerTest}.
 *
 * <p>The schema comes from the Flyway migrations production runs, not Hibernate.
 *
 * <p>Why this is the way it is: per-test isolation is the plain {@code @AfterEach} deletion in
 * {@link #cleanup()}, never test-managed {@code @Transactional} rollback, which was rejected on
 * three verified grounds. It never delivers {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} events, since a rolled-back transaction never commits. It corrupts {@link
 * #countQueries(Runnable)}'s {@code getPrepareStatementCount()} metric, since a shared persistence
 * context across {@code @BeforeEach} and the act keeps fixtures in the first-level cache and hides
 * {@code findById()} calls. And it gives {@code AbstractAppE2ETest}'s cross-thread HTTP round trips
 * no isolation, since the test thread's transaction never spans the request.
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
     * A board owned by {@link #getForeignUser()}, not {@link #getOwningUser()}, and distinct from
     * {@link #getNoBoardsUser()}, which owns nothing.
     *
     * <p>The foreign user owns a genuine board and column, so a cross-user test against {@link
     * #getForeignUserColumn()} proves a legitimate owner is still refused someone else's resource,
     * not merely that an empty account sees nothing.
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

        // foreign user: created last so it never shifts the insertion order that
        // boardService.findAll() etc. return for the owning user's fixtures, which tests assert on
        // positionally (e.g. BoardServiceTest.testUpdateById_shouldUpdateBoard_whenBoardExists).
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
     * Deletes every row a test left behind; this hook is the codebase's single isolation model.
     *
     * <p>Why this is the way it is: {@code activity_log} is the one table unreachable from {@link
     * UserService#deleteAll()}'s cascade, because {@code V3__add_activity_log.sql} declares {@code
     * board_id}/{@code user_id} as plain {@code NOT NULL} columns with no foreign key, a deliberate
     * entity-level design choice (see {@link com.vrudenko.kanban_board.entity.ActivityLogEntity}'s
     * Javadoc), not an oversight to fix with a migration. A long-lived container does not mask that
     * gap, so the second call below closes it explicitly. A future author should extend this method
     * for a new FK-less table, not add a second isolation mechanism. The third call clears {@link
     * RecordingActivityEventListener}'s singleton {@code CopyOnWriteArrayList}, shared across every
     * test class in a JVM fork. That is safe because {@link
     * RecordingActivityEventListener#onActivityEvent} has no {@code @Async} (unlike {@code
     * KafkaEventPublisher.onActivityEvent}), so it runs synchronously on the committing thread and
     * every event this test produced is recorded before this hook runs; nothing queued on another
     * thread can be lost. Every test asserting on the recorder already clears it in its own arrange
     * block, so this changes accumulation across the run, not what any test observes.
     */
    @AfterEach
    void cleanup() {
        userService.deleteAll();
        activityLogRepository.deleteAll();
        recordingActivityEventListener.clear();
    }

    /**
     * Returns a password satisfying {@link Password}.
     *
     * <p>It has a lowercase letter, an uppercase letter, a digit and a special character, with
     * length between {@link ValidationConstants#MIN_PASSWORD_LENGTH} and {@link
     * ValidationConstants#MAX_PASSWORD_LENGTH}. Fixture passwords must satisfy {@code @Password}
     * because {@link AbstractAppMockMvcTest#signinCookie()} posts them to the real {@code POST
     * /signin} route, which validates its request body.
     */
    protected String generateValidPassword() {
        var base =
                dataFactory
                        .getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH)
                        .toLowerCase(Locale.ROOT);

        // "Aa1!" adds an uppercase letter, a digit and a special character to the lowercased base
        // word, satisfying every @Password class whatever dataFactory generated.
        return base + "Aa1!";
    }

    /**
     * Returns an email guaranteed to satisfy {@code @AppEmail}'s {@code @Email} format constraint.
     *
     * <p>Why this is the way it is: it does not use {@code dataFactory.getEmailAddress()}. Its
     * word-based local-part branch draws from a small, dirty corpus containing literal multi-word
     * phrases (e.g. the entry {@code "or maybe"}) and concatenates them with a second word with no
     * separator, occasionally producing an email with an embedded space (e.g. {@code "or
     * maybedreams@ma1lbox.org"}). That fails {@code @Email}, but the {@code
     * ReportAsSingleViolation} on {@code @AppEmail} collapses the failure into the generic {@code
     * "Email cannot be empty"} message whichever sub-constraint failed: a confusing bug in the test
     * fixtures, not in {@code AuthenticationController} or validation. Root-caused with a temporary
     * diagnostic on {@link AbstractAppMockMvcTest#signinCookie(String, String)} that captured the
     * malformed value from a live failure, and confirmed by decompiling {@code
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
     * Creates a board+column owned by an arbitrary user, for fixtures owned by someone other than
     * {@link #getOwningUser()}.
     *
     * <p>There is no REST endpoint for creating a board directly (boards are created via {@link
     * com.vrudenko.kanban_board.service.UserService#addBoardByUserId}), so this goes through the
     * service layer.
     */
    protected ColumnResponseDTO createColumnForUser(
            String userId, String boardName, String columnName) {
        var board = createBoardForUser(userId, boardName);

        return boardService.addColumnByBoardId(
                userId, board.getId(), SaveColumnRequestDTO.builder().name(columnName).build());
    }

    /**
     * Creates a board owned by an arbitrary user, without a column; the sibling of {@link
     * #createColumnForUser(String, String, String)} for callers that need the board itself.
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
     * Returns the number of JDBC statements Hibernate prepared while running {@code action}.
     *
     * <p>Uses {@code getPrepareStatementCount()}, not {@code getQueryExecutionCount()}, because the
     * latter counts only HQL/JPQL queries, not the {@code find()}-by-id lookups that {@code
     * repository.findById()} compiles to.
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

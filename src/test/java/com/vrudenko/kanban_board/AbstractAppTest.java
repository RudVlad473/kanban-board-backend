package com.vrudenko.kanban_board;

import com.google.common.collect.ImmutableList;
import com.vrudenko.kanban_board.constant.ValidationConstants;
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
import jakarta.persistence.EntityManagerFactory;
import java.util.stream.Stream;
import lombok.Getter;
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

    private @Autowired EntityManagerFactory entityManagerFactory;

    protected final DataFactory dataFactory = new DataFactory();

    protected final int MOCK_COLUMNS_AMOUNT = 7;
    protected final int MOCK_TASKS_AMOUNT = 7;
    protected final int MOCK_SUBTASKS_AMOUNT = 7;

    // users
    @Getter
    private final String owningUserPassword =
            dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH);

    @Getter private UserResponseDTO owningUser;

    @Getter
    private final String noBoardsUserPassword =
            dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH);

    @Getter private UserResponseDTO noBoardsUser;

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
     */
    @AfterEach
    void cleanup() {
        userService.deleteAll();
        activityLogRepository.deleteAll();
    }

    protected UserResponseDTO createUser() {
        return createUser(dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH));
    }

    protected UserResponseDTO createUser(String password) {
        return userService.save(
                SignupRequestDTO.builder()
                        .email(dataFactory.getEmailAddress())
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
        var board =
                userService.addBoardByUserId(
                        userId, SaveBoardRequestDTO.builder().name(boardName).build());

        return boardService.addColumnByBoardId(
                userId, board.getId(), SaveColumnRequestDTO.builder().name(columnName).build());
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

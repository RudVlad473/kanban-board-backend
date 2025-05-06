package com.vrudenko.kanban_board.service;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.SaveTaskRequestDTO;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import com.vrudenko.kanban_board.mapper.UserMapper;
import lombok.Getter;
import org.fluttercode.datafactory.impl.DataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public abstract class AbstractAppServiceTest {
  private @Autowired UserService userService;
  private @Autowired UserMapper userMapper;
  private @Autowired BoardService boardService;
  private @Autowired BoardMapper boardMapper;
  private @Autowired ColumnService columnService;
  private @Autowired TaskService taskService;

  final DataFactory dataFactory = new DataFactory();

  protected final int MOCK_COLUMNS_AMOUNT = 7;
  protected final int MOCK_TASKS_AMOUNT = 7;

  // users
  @Getter private UserResponseDTO owningUser;
  @Getter private UserResponseDTO noBoardsUser;

  // boards
  protected ImmutableList<BoardResponseDTO> mockEmptyBoards = ImmutableList.of();
  protected BoardResponseDTO mockPopulatedBoard = BoardResponseDTO.builder().build();

  // columns
  protected ImmutableList<ColumnResponseDTO> mockColumns = ImmutableList.of();
  protected ColumnResponseDTO mockPopulatedColumn = ColumnResponseDTO.builder().build();

  // tasks
  protected ImmutableList<TaskResponseDTO> mockTasks = ImmutableList.of();

  @BeforeEach
  void setup() {
    // user
    owningUser = setupUser();
    noBoardsUser = setupUser();

    // board
    mockEmptyBoards =
        ImmutableList.copyOf(
            Stream.of("Todo", "Done")
                .map(
                    (boardName) ->
                        boardService.save(
                            getOwningUser().getId(),
                            SaveBoardRequestDTO.builder().name(boardName).build()))
                .toList());
    mockPopulatedBoard =
        boardService.save(
            getOwningUser().getId(), SaveBoardRequestDTO.builder().name("In progress").build());

    // column
    mockColumns =
        ImmutableList.copyOf(
            Stream.generate(
                    () -> dataFactory.getRandomWord(ValidationConstants.MIN_COLUMN_NAME_LENGTH))
                .limit(MOCK_COLUMNS_AMOUNT)
                .map(
                    columnName ->
                        boardService.addColumnByBoardId(
                            getOwningUser().getId(),
                            mockPopulatedBoard.getId(),
                            SaveColumnRequestDTO.builder().name(columnName).build()))
                .toList());
    mockPopulatedColumn =
        columnService.save(
            SaveColumnRequestDTO.builder()
                .name(dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4))
                .build(),
            boardService.findById(getOwningUser().getId(), mockPopulatedBoard.getId()));

    // task
    mockTasks =
        ImmutableList.copyOf(
            Stream.generate(() -> null)
                .limit(MOCK_TASKS_AMOUNT)
                .map((ignore) -> setupTask())
                .toList());
  }

  @AfterEach
  void cleanup() {
    // board
    boardService.deleteAll();

    // user
    userService.deleteAll();
  }

  UserResponseDTO setupUser() {
    return userService.save(
        userMapper.toSigninRequestDTO(
            UserEntity.builder()
                .email(dataFactory.getEmailAddress())
                .displayName(
                    dataFactory.getRandomWord(ValidationConstants.MIN_USER_DISPLAY_NAME_LENGTH))
                .passwordHash(dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH))
                .build()));
  }

  TaskResponseDTO setupTask() {
    return columnService.addTaskByColumnId(
        getOwningUser().getId(),
        mockPopulatedColumn.getId(),
        SaveTaskRequestDTO.builder()
            .title(dataFactory.getRandomWord(ValidationConstants.MIN_TASK_TITLE_LENGTH + 2))
            .description(
                dataFactory.getRandomText(
                    ValidationConstants.MIN_TASK_DESCRIPTION_LENGTH,
                    ValidationConstants.MAX_TASK_DESCRIPTION_LENGTH))
            .build());
  }
}

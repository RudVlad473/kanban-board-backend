package com.vrudenko.kanban_board.service;

import com.google.common.collect.ImmutableList;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import java.util.UUID;
import java.util.stream.Stream;

import com.vrudenko.kanban_board.mapper.UserMapper;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.fluttercode.datafactory.impl.DataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

@SpringBootTest
public class BoardServiceTest {

  @Autowired BoardService boardService;
  @Autowired BoardMapper boardMapper;
  @Autowired UserService userService;
  @Autowired UserMapper userMapper;

  final DataFactory dataFactory = new DataFactory();

  private UserResponseDTO owningUser;
  private UserResponseDTO noBoardsUser;
  final ImmutableList<BoardEntity> mockBoards =
      ImmutableList.copyOf(
          Stream.of("Todo", "In progress", "Done")
              .map((name) -> BoardEntity.builder().name(name).build())
              .toList());

  @BeforeEach
  void setup() {
    owningUser = setupUser();
    noBoardsUser = setupUser();

    mockBoards.forEach(
        (board) -> boardService.save(owningUser.getId(), boardMapper.toSaveBoardRequestDTO(board)));
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

  @AfterEach
  void cleanup() {
    deleteAllBoards();
    userService.deleteById(owningUser.getId());
    userService.deleteById(noBoardsUser.getId());
  }

  void deleteAllBoards() {
    boardService
        .findAll()
        .forEach((board) -> boardService.deleteById(owningUser.getId(), board.getId()));
  }

  // findAll
  @Test
  void testFindAll_shouldReturnBoards_whenBoardExists() {
    // Act
    var boards = boardService.findAll();

    // Assert
    Assertions.assertThat(boards).hasSize(mockBoards.size());
  }

  // deleteById
  @Test
  void testDeleteById_shouldDeleteBoard_whenBoardExists() {
    // Arrange

    // Act
    var firstBoard = boardService.findAll().getFirst();
    boardService.deleteById(owningUser.getId(), firstBoard.getId());

    // Assert
    var boards = boardService.findAll();
    Assertions.assertThat(boards.size()).isEqualTo(mockBoards.size() - 1);
    Assertions.assertThat(boards).doesNotContain(firstBoard);
  }

  @Test
  void testDeleteById_shouldDeleteAllBoards_whenBoardsExists() {
    // Arrange

    // Act
    boardService
        .findAll()
        .forEach(board -> boardService.deleteById(owningUser.getId(), board.getId()));

    // Assert
    var boards = boardService.findAll();
    Assertions.assertThat(boards.size()).isZero();
  }

  @Test
  void testDeleteById_shouldReturnFalse_whenBoardNotFound() {
    // Arrange
    var firstBoard = boardService.findAll().getFirst();
    deleteAllBoards();

    // Act
    var wasBoardDeleted = boardService.deleteById(owningUser.getId(), firstBoard.getId());

    // Assert
    Assertions.assertThat(wasBoardDeleted).isFalse();
  }

  @Test
  void testDeleteById_shouldReturnTrue_whenBoardFoundAndDeleted() {
    // Arrange
    var firstBoard = boardService.findAll().getFirst();

    // Act
    var wasBoardDeleted = boardService.deleteById(owningUser.getId(), firstBoard.getId());

    // Assert
    Assertions.assertThat(wasBoardDeleted).isTrue();
  }

  @Test
  void testDeleteById_shouldNotDelete_whenBoardDoesntBelongToUser() {
    // Arrange
    var firstBoard = boardService.findAll().getFirst();

    // Act
    var thrown =
        Assertions.catchThrowable(
            () -> boardService.deleteById(noBoardsUser.getId(), firstBoard.getId()));

    // Assert
    Assertions.assertThat(thrown).isInstanceOf(AccessDeniedException.class);
    Assertions.assertThat(boardService.findAll()).hasSameSizeAs(mockBoards);
  }

  // update by id
  @Test
  void testUpdateById_shouldUpdateBoard_whenBoardExists() {
    // Arrange
    var boardBeforeUpdate = boardService.findAll().getFirst();
    var newBoardName =
        RandomStringUtils.randomAlphabetic(
            ValidationConstants.MAX_BOARD_NAME_LENGTH - ValidationConstants.MIN_BOARD_NAME_LENGTH);

    // Act
    boardService.updateById(
        owningUser.getId(),
        boardBeforeUpdate.getId(),
        boardMapper.toSaveBoardRequestDTO(BoardEntity.builder().name(newBoardName).build()));

    // Assert
    var boardAfterUpdate =
        boardService.findAll().stream()
            .filter(board -> board.getId().equals(boardBeforeUpdate.getId()))
            .toList()
            .getFirst();

    Assertions.assertThat(boardBeforeUpdate.getName()).isNotEqualTo(boardAfterUpdate.getName());
    Assertions.assertThat(boardAfterUpdate.getName()).isEqualTo(newBoardName);
    Assertions.assertThat(boardBeforeUpdate.getId()).isEqualTo(boardAfterUpdate.getId());
  }

  @Test
  void testUpdateById_shouldNotUpdateBoard_whenBoardDoesntExist() {
    // Arrange
    var randomUUID = UUID.randomUUID().toString();
    var newBoardName =
        RandomStringUtils.randomAlphabetic(
            ValidationConstants.MAX_BOARD_NAME_LENGTH - ValidationConstants.MIN_BOARD_NAME_LENGTH);

    // Act
    var emptyBoard =
        boardService.updateById(
            owningUser.getId(),
            randomUUID,
            boardMapper.toSaveBoardRequestDTO(BoardEntity.builder().name(newBoardName).build()));

    // Assert
    Assertions.assertThat(emptyBoard.isEmpty()).isTrue();
    Assertions.assertThat(
            boardService.findAll().stream()
                .noneMatch(board -> board.getName().equals(newBoardName)))
        .isTrue();
  }

  @Test
  void testUpdateById_shouldNotUpdateBoard_whenBoardDoesntBelongToUser() {
    // Arrange
    var existingBoard = boardService.findAll().getFirst();
    var newBoardName =
        RandomStringUtils.randomAlphabetic(
            ValidationConstants.MAX_BOARD_NAME_LENGTH - ValidationConstants.MIN_BOARD_NAME_LENGTH);

    // Act
    var thrown =
        Assertions.catchThrowable(
            () ->
                boardService.updateById(
                    noBoardsUser.getId(),
                    existingBoard.getId(),
                    boardMapper.toSaveBoardRequestDTO(
                        BoardEntity.builder().name(newBoardName).build())));
    var boardAfterFailedUpdate = boardService.findAll().getFirst();

    // Assert
    Assertions.assertThat(thrown).isInstanceOf(AccessDeniedException.class);
    Assertions.assertThat(
            boardService.findAll().stream()
                .noneMatch(board -> board.getName().equals(newBoardName)))
        .isTrue();
    Assertions.assertThat(existingBoard).isEqualTo(boardAfterFailedUpdate);
  }

  // findAllByUserId
  @Test
  void testFindAllByUserId_shouldReturnBoard_whenBoardExists() {
    // arrange
    var user = userService.findAll().getFirst();

    // act
    var boards = boardService.findAllByUserId(user.getId());

    // assert
    Assertions.assertThat(boards).hasSameSizeAs(mockBoards);
  }

  @Test
  void testFindAllByUserId_shouldReturnEmptyList_whenBoardDoesntExist() {
    // arrange
    deleteAllBoards();
    var user = userService.findAll().getFirst();

    // act
    var boards = boardService.findAllByUserId(user.getId());

    // assert
    Assertions.assertThat(boards).isEmpty();
  }
}

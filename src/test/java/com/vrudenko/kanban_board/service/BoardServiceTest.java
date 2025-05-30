package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.column_dto.SaveColumnRequestDTO;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

@SpringBootTest
public class BoardServiceTest extends AbstractAppTest {
  @Autowired UserService userService;
  @Autowired BoardService boardService;
  @Autowired BoardMapper boardMapper;
  @Autowired ColumnService columnService;

  // addColumnByBoardId
  @Test
  void testAddColumnByBoardId_shouldReturnColumn_whenBoardExists() {
    // Arrange
    var userId = getOwningUser().getId();
    var boardId = mockPopulatedBoard.getId();
    var columnAmountBeforeAddition = columnService.getColumnCountByBoardId(boardId);
    var columnName = dataFactory.getRandomWord(ValidationConstants.MIN_COLUMN_NAME_LENGTH);

    // Act
    var column =
        boardService.addColumnByBoardId(
            userId, boardId, SaveColumnRequestDTO.builder().name(columnName).build());

    // Assert
    var columnAmountAfterAddition = columnService.getColumnCountByBoardId(boardId);

    Assertions.assertThat(columnAmountAfterAddition).isEqualTo(columnAmountBeforeAddition + 1);
    Assertions.assertThat(columnService.findById(getOwningUser().getId(), column.getId()))
        .isNotInstanceOf(AppEntityNotFoundException.class);
    Assertions.assertThat(columnService.findById(getOwningUser().getId(), column.getId()).getName())
        .isEqualTo(columnName);
  }

  // findAll
  @Test
  void testFindAll_shouldReturnBoards_whenBoardExists() {
    // Act
    var boards = boardService.findAll();

    // Assert
    Assertions.assertThat(boards).isNotEmpty();
  }

  // deleteById
  @Test
  void testDeleteById_shouldDeleteBoard_whenBoardExists() {
    // Arrange
    var userId = getOwningUser().getId();
    var boardId = mockPopulatedBoard.getId();
    var boardsAmountBeforeDeletion = boardService.findAllByUserId(userId).size();

    // Act
    boardService.deleteById(userId, boardId);

    // Assert
    var boardsAmountAfterDeletion = boardService.findAllByUserId(userId).size();
    Assertions.assertThat(boardsAmountAfterDeletion).isEqualTo(boardsAmountBeforeDeletion - 1);
    Assertions.assertThat(Assertions.catchException(() -> boardService.findById(userId, boardId)))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void testDeleteById_shouldDeleteAllBoards_whenBoardsExists() {
    // Arrange
    var userId = getOwningUser().getId();

    // Act
    boardService
        .findAllByUserId(userId)
        .forEach(board -> boardService.deleteById(userId, board.getId()));

    // Assert
    var boards = boardService.findAllByUserId(userId);
    Assertions.assertThat(boards).isEmpty();
  }

  @Test
  void testDeleteById_shouldReturnFalse_whenBoardNotFound() {
    // Arrange
    var userId = getOwningUser().getId();
    var boardsBeforeDeletion = boardService.findAllByUserId(userId);
    var firstBoardBeforeDeletion = boardsBeforeDeletion.getFirst();

    boardsBeforeDeletion.forEach(board -> boardService.deleteById(userId, board.getId()));

    // Act
    var exception =
        Assertions.catchException(
            () -> boardService.deleteById(userId, firstBoardBeforeDeletion.getId()));

    // Assert
    Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
  }

  @Test
  void testDeleteById_shouldReturnTrue_whenBoardFoundAndDeleted() {
    // Arrange
    var userId = getOwningUser().getId();
    var boardId = mockPopulatedBoard.getId();

    // Act
    boardService.deleteById(userId, boardId);

    // Assert
    Assertions.assertThat(Assertions.catchException(() -> boardService.findById(userId, boardId)))
        .isInstanceOf(AppEntityNotFoundException.class);
  }

  @Test
  void testDeleteById_shouldNotDelete_whenBoardDoesntBelongToUser() {
    // Arrange
    var userId = getNoBoardsUser().getId();
    var boardId = mockPopulatedBoard.getId();
    var boardAmountBeforeAttempt = boardService.findAll().size();

    // Act
    var exception = Assertions.catchException(() -> boardService.deleteById(userId, boardId));

    // Assert
    var boardAmountAfterAttempt = boardService.findAll().size();
    Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
    Assertions.assertThat(boardAmountBeforeAttempt).isSameAs(boardAmountAfterAttempt);
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
        getOwningUser().getId(),
        boardBeforeUpdate.getId(),
        boardMapper.toUpdateBoardRequestDTO(BoardEntity.builder().name(newBoardName).build()));

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
    var exception =
        Assertions.catchException(
            () ->
                boardService.updateById(
                    getOwningUser().getId(),
                    randomUUID,
                    boardMapper.toUpdateBoardRequestDTO(
                        BoardEntity.builder().name(newBoardName).build())));

    // Assert
    Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
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
                    getNoBoardsUser().getId(),
                    existingBoard.getId(),
                    boardMapper.toUpdateBoardRequestDTO(
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
    var userId = getOwningUser().getId();

    // act
    var boards = boardService.findAllByUserId(userId);

    // assert
    Assertions.assertThat(boards).isNotEmpty();
  }

  @Test
  void testFindAllByUserId_shouldReturnEmptyList_whenBoardDoesntExist() {
    // arrange
    boardService.deleteAll();
    var user = userService.findAll().getFirst();

    // act
    var boards = boardService.findAllByUserId(user.getId());

    // assert
    Assertions.assertThat(boards).isEmpty();
  }
}

package com.vrudenko.kanban_board.service;

import com.google.common.collect.ImmutableList;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class BoardServiceTest {

  @Autowired BoardService boardService;
  @Autowired BoardMapper boardMapper;

  final ImmutableList<BoardEntity> mockBoards =
      ImmutableList.copyOf(
          Stream.of("Todo", "In progress", "Done")
              .map((name) -> BoardEntity.builder().name(name).build())
              .toList());

  @BeforeEach
  void setup() {
    // Arrange
    mockBoards.forEach((board) -> boardService.save(boardMapper.toSaveBoardRequestDTO(board)));
  }

  @AfterEach
  void cleanup() {
    deleteAllBoards();
  }

  void deleteAllBoards() {
    boardService.findAll().forEach((board) -> boardService.deleteById(board.getId()));
  }

  @Test
  void testFindAll_shouldReturnBoards_whenBoardExists() {
    // Act
    var boards = boardService.findAll();

    // Assert
    Assertions.assertThat(boards).isNotNull();
    Assertions.assertThat(boards.size()).isEqualTo(mockBoards.size());
  }

  @Test
  void testDeleteById_shouldDeleteBoard_whenBoardExists() {
    // Arrange

    // Act
    var firstBoard = boardService.findAll().getFirst();
    boardService.deleteById(firstBoard.getId());

    // Assert
    var boards = boardService.findAll();
    Assertions.assertThat(boards.size()).isEqualTo(mockBoards.size() - 1);
    Assertions.assertThat(boards).doesNotContain(firstBoard);
  }

  @Test
  void testDeleteById_shouldDeleteAllBoards_whenBoardsExists() {
    // Arrange

    // Act
    boardService.findAll().forEach(board -> boardService.deleteById(board.getId()));

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
    var wasBoardDeleted = boardService.deleteById(firstBoard.getId());

    // Assert
    Assertions.assertThat(wasBoardDeleted).isFalse();
  }

  @Test
  void testDeleteById_shouldReturnTrue_whenBoardFoundAndDeleted() {
    // Arrange
    var firstBoard = boardService.findAll().getFirst();

    // Act
    var wasBoardDeleted = boardService.deleteById(firstBoard.getId());

    // Assert
    Assertions.assertThat(wasBoardDeleted).isTrue();
  }

  @Test
  void testUpdateById_shouldUpdateBoard_whenBoardExists() {
    // Arrange
    var boardBeforeUpdate = boardService.findAll().getFirst();
    var newBoardName =
        RandomStringUtils.randomAlphabetic(
            ValidationConstants.MAX_BOARD_NAME_LENGTH - ValidationConstants.MIN_BOARD_NAME_LENGTH);

    // Act
    boardService.updateById(
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
            randomUUID,
            boardMapper.toSaveBoardRequestDTO(BoardEntity.builder().name(newBoardName).build()));

    // Assert
    Assertions.assertThat(emptyBoard.isEmpty()).isTrue();
    Assertions.assertThat(
            boardService.findAll().stream()
                .noneMatch(board -> board.getName().equals(newBoardName)))
        .isTrue();
  }
}

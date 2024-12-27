package com.vrudenko.kanban_board.service;

import com.google.common.collect.ImmutableList;
import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.mapper.BoardMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class BoardServiceTest {

  @Autowired BoardService boardService;
  @Autowired BoardMapper boardMapper;

  private final ImmutableList<BoardEntity> mockBoards =
      ImmutableList.of(
          BoardEntity.builder().name("Todo").build(),
          BoardEntity.builder().name("In progress").build(),
          BoardEntity.builder().name("Done").build());

  @Test
  void testFindAll_shouldReturnBoards_whenBoardExists() {
    // Arrange
    mockBoards.forEach((board) -> boardService.save(boardMapper.toSaveBoardRequestDTO(board)));

    // Act
    var boards = boardService.findAll();

    // Assert
    Assertions.assertThat(boards).isNotNull();
    Assertions.assertThat(boards.size()).isEqualTo(mockBoards.size());
  }
}

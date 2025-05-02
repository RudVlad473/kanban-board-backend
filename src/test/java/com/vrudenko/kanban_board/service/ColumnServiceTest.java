package com.vrudenko.kanban_board.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ColumnServiceTest extends AbstractAppServiceTest {
  @Autowired ColumnService columnService;

  // findAllByBoardId
  @Test
  void testFindAllByBoardId_shouldReturnColumns_whenColumnsExist() {
    // Arrange
    var boardId = mockPopulatedBoard.getId();

    // Act
    var columns = columnService.findAllByBoardId(boardId);

    // Assert
    Assertions.assertThat(columns).isNotEmpty();
    Assertions.assertThat(columns).hasSameSizeAs(mockColumns);
    for (var column : columns) {
      var columnEntity = columnService.findById(column.getId());

      Assertions.assertThat(columnEntity).isPresent();
      Assertions.assertThat(columnEntity.get().getBoard().getId()).isEqualTo(boardId);
    }
  }

  @Test
  void testFindAllByBoardId_shouldReturnEmptyList_whenBoardIsEmpty() {
    // Arrange
    var userId = getOwningUser().getId();
    var boardId = mockEmptyBoards.getFirst().getId();

    // Act
    var columns = columnService.findAllByBoardId(boardId);

    // Assert
    Assertions.assertThat(columns).isEmpty();
  }
}

package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.entity.BoardEntity;
import com.vrudenko.kanban_board.entity.UserEntity;
import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class OwnershipVerifierServiceTest extends AbstractAppServiceTest {
  @Autowired OwnershipVerifierService ownershipVerifierService;

  @Nested
  class VerifyOwnershipOfBoardTest {
    @Test
    void shouldReturnUserAndBoard_whenUserOwnsTheBoard() {
      // Arrange
      var userId = getOwningUser().getId();
      var boardId = mockPopulatedBoard.getId();

      // Act
      var pair = ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId);

      // Assert
      Assertions.assertThat(pair.getFirst().getId()).isSameAs(userId);
      Assertions.assertThat(pair.getSecond().getId()).isSameAs(boardId);
      Assertions.assertThat(pair.getFirst()).isInstanceOf(UserEntity.class);
      Assertions.assertThat(pair.getSecond()).isInstanceOf(BoardEntity.class);
    }

    @Test
    void shouldThrow_whenUserDoesntOwnBoard() {
      // Arrange
      var userId = getNoBoardsUser().getId();
      var boardId = mockPopulatedBoard.getId();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
    }

    @Test
    void shouldThrow_whenBoardDoesntExist() {
      // Arrange
      var userId = getOwningUser().getId();
      var boardId = UUID.randomUUID().toString();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }

    @Test
    void shouldThrow_whenUserDoesntExist() {
      // Arrange
      var userId = UUID.randomUUID().toString();
      var boardId = mockPopulatedBoard.getId();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfBoard(userId, boardId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }
  }

  @Nested
  class VerifyOwnershipOfColumnTest {
    @Test
    void shouldReturnUserAndColumn_whenUserOwnsTheColumn() {
      // Arrange
      var userId = getOwningUser().getId();
      var columnId = mockColumns.getFirst().getId();

      // Act
      var pair = ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId);

      // Assert
      Assertions.assertThat(pair.getFirst().getId()).isSameAs(userId);
      Assertions.assertThat(pair.getSecond().getId()).isSameAs(columnId);
    }

    @Test
    void shouldThrow_whenUserDoesntOwnColumn() {
      // Arrange
      var userId = getNoBoardsUser().getId();
      var columnId = mockColumns.getFirst().getId();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
    }

    @Test
    void shouldThrow_whenColumnDoesntExist() {
      // Arrange
      var userId = getOwningUser().getId();
      var columnId = UUID.randomUUID().toString();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }

    @Test
    void shouldThrow_whenUserDoesntExist() {
      // Arrange
      var userId = UUID.randomUUID().toString();
      var columnId = mockColumns.getFirst().getId();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfColumn(userId, columnId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }
  }

  @Nested
  class VerifyOwnershipOfTaskTest {
    @Test
    void shouldReturnUserAndTask_whenUserOwnsTheTask() {
      // Arrange
      var userId = getOwningUser().getId();
      var taskId = mockTasks.getFirst().getId();

      // Act
      var pair = ownershipVerifierService.verifyOwnershipOfTask(userId, taskId);

      // Assert
      Assertions.assertThat(pair.getFirst().getId()).isSameAs(userId);
      Assertions.assertThat(pair.getSecond().getId()).isSameAs(taskId);
    }

    @Test
    void shouldThrow_whenUserDoesntOwnTask() {
      // Arrange
      var userId = getNoBoardsUser().getId();
      var taskId = mockTasks.getFirst().getId();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfTask(userId, taskId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
    }

    @Test
    void shouldThrow_whenTaskDoesntExist() {
      // Arrange
      var userId = getOwningUser().getId();
      var taskId = UUID.randomUUID().toString();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfTask(userId, taskId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }

    @Test
    void shouldThrow_whenUserDoesntExist() {
      // Arrange
      var userId = UUID.randomUUID().toString();
      var taskId = mockTasks.getFirst().getId();

      // Act
      var exception =
          Assertions.catchException(
              () -> ownershipVerifierService.verifyOwnershipOfColumn(userId, taskId));

      // Assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }
  }
}

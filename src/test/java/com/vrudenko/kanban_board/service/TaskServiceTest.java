package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.exception.AppAccessDeniedException;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
public class TaskServiceTest extends AbstractAppServiceTest {
  @Autowired TaskService taskService;

  @Nested
  class FindAllByColumnIdTest {
    @Test
    void shouldReturn_whenColumnExists() {
      // arrange
      final var userId = getOwningUser().getId();
      final var columnId = mockPopulatedColumn.getId();

      // act
      var tasks = taskService.findAllByColumnId(userId, columnId);

      // assert
      Assertions.assertThat(tasks).isNotEmpty();
    }

    @Test
    void shouldReturn_whenColumnIsEmpty() {
      // arrange
      final var userId = getOwningUser().getId();
      final var columnId = mockColumns.getFirst().getId();

      // act
      var tasks = taskService.findAllByColumnId(userId, columnId);

      // assert
      Assertions.assertThat(tasks).isEmpty();
    }

    @Test
    void shouldThrow_whenColumnDoesntExist() {
      // arrange
      final var userId = getOwningUser().getId();
      final var columnId = UUID.randomUUID().toString();

      // act
      var exception =
          Assertions.catchException(() -> taskService.findAllByColumnId(userId, columnId));

      // assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }

    @Test
    void shouldThrow_whenUserDoesntOwnTheColumn() {
      // arrange
      final var userId = getNoBoardsUser().getId();
      final var columnId = mockPopulatedColumn.getId();

      // act
      var exception =
          Assertions.catchException(() -> taskService.findAllByColumnId(userId, columnId));

      // assert
      Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
    }

    @Test
    void shouldThrow_whenUserDoesntExist() {
      // arrange
      final var userId = UUID.randomUUID().toString();
      final var columnId = mockPopulatedBoard.getId();

      // act
      var exception =
          Assertions.catchException(() -> taskService.findAllByColumnId(userId, columnId));

      // assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }
  }

  @Nested
  class FindAllByIdTest {
    @Test
    void shouldReturn_whenTaskExists() {
      // arrange
      final var userId = getOwningUser().getId();
      final var columnId = mockPopulatedColumn.getId();

      // act
      var tasks = taskService.findAllByColumnId(userId, columnId);

      // assert
      Assertions.assertThat(tasks).isNotEmpty();
    }

    @Test
    void shouldThrow_whenTaskDoesntExist() {
      // arrange
      final var userId = getOwningUser().getId();
      final var columnId = UUID.randomUUID().toString();

      // act
      var exception =
          Assertions.catchException(() -> taskService.findAllByColumnId(userId, columnId));

      // assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }

    @Test
    void shouldThrow_whenUserDoesntOwnTheColumn() {
      // arrange
      final var userId = getNoBoardsUser().getId();
      final var columnId = mockPopulatedColumn.getId();

      // act
      var exception =
          Assertions.catchException(() -> taskService.findAllByColumnId(userId, columnId));

      // assert
      Assertions.assertThat(exception).isInstanceOf(AppAccessDeniedException.class);
    }

    @Test
    void shouldThrow_whenUserDoesntExist() {
      // arrange
      final var userId = UUID.randomUUID().toString();
      final var columnId = mockPopulatedBoard.getId();

      // act
      var exception =
          Assertions.catchException(() -> taskService.findAllByColumnId(userId, columnId));

      // assert
      Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    }
  }
}

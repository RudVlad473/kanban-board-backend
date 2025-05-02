package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class UserServiceTest extends AbstractAppServiceTest {
  final int AMOUNT_OF_FAKE_USERS = 5;

  @Autowired UserService userService;

  List<UserResponseDTO> mockUsers = new ArrayList<>();

  @BeforeEach
  void setup() {
    for (var i = 0; i < AMOUNT_OF_FAKE_USERS; i++) {
      mockUsers.add(setupUser());
    }
  }

  @Test
  void testFindAll_shouldReturnUser_whenUserExists() {
    // Arrange
    var firstUser = userService.findAll().getFirst();

    // Act
    var foundUser = userService.findById(firstUser.getId());

    // Assert
    Assertions.assertThat(foundUser.getId()).isEqualTo(firstUser.getId());
    Assertions.assertThat(foundUser.getDisplayName()).isEqualTo(firstUser.getDisplayName());
    Assertions.assertThat(foundUser.getEmail()).isEqualTo(firstUser.getEmail());
  }

  @Test
  void testFindAll_shouldReturnEmpty_whenUserDoesntExists() {
    // Arrange
    var randomUUID = UUID.randomUUID().toString();

    // Act
    var exception = Assertions.catchException(() -> userService.findById(randomUUID));

    // Assert
    Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
    Assertions.assertThat(
            userService.findAll().stream().noneMatch(user -> user.getId().equals(randomUUID)))
        .isTrue();
  }

  @Test
  void testDeleteById_shouldDeleteUser_whenUserExists() {
    // Arrange

    // Act
    var firstUser = userService.findAll().getFirst();
    userService.deleteById(firstUser.getId());

    // Assert
    var users = userService.findAll();
    Assertions.assertThat(users.size()).isEqualTo(mockUsers.size() - 1);
    Assertions.assertThat(users).doesNotContain(firstUser);
  }

  @Test
  void testDeleteById_shouldDeleteAllUsers_whenUsersExists() {
    // Arrange

    // Act
    userService.findAll().forEach(user -> userService.deleteById(user.getId()));

    // Assert
    var users = userService.findAll();
    Assertions.assertThat(users.size()).isZero();
  }

  @Test
  void testDeleteById_shouldReturnFalse_whenUserNotFound() {
    // Arrange
    var firstUser = userService.findAll().getFirst();
    userService.deleteAll();

    // Act
    var wasUserDeleted = userService.deleteById(firstUser.getId());

    // Assert
    Assertions.assertThat(wasUserDeleted).isFalse();
  }

  @Test
  void testDeleteById_shouldReturnTrue_whenUserFoundAndDeleted() {
    // Arrange
    var firstUser = userService.findAll().getFirst();

    // Act
    var wasUserDeleted = userService.deleteById(firstUser.getId());

    // Assert
    Assertions.assertThat(wasUserDeleted).isTrue();
  }
}

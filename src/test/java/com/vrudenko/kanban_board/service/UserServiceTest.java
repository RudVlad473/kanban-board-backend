package com.vrudenko.kanban_board.service;

import com.google.common.collect.ImmutableList;
import com.vrudenko.kanban_board.entity.UserEntity;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.vrudenko.kanban_board.mapper.UserMapper;
import org.assertj.core.api.Assertions;
import org.fluttercode.datafactory.impl.DataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class UserServiceTest {
  final int AMOUNT_OF_FAKE_USERS = 5;

  DataFactory dataFactory = new DataFactory();

  @Autowired UserService userService;
  @Autowired UserMapper userMapper;

  final ImmutableList<String> mockEmails =
      ImmutableList.copyOf(
          IntStream.range(0, AMOUNT_OF_FAKE_USERS)
              .mapToObj(i -> dataFactory.getEmailAddress())
              .collect(Collectors.toList()));

  final ImmutableList<String> mockDisplayNames =
      ImmutableList.copyOf(
          IntStream.range(0, AMOUNT_OF_FAKE_USERS)
              .mapToObj(i -> dataFactory.getName())
              .collect(Collectors.toList()));

  final ImmutableList<UserEntity> mockUsers =
      ImmutableList.copyOf(
          IntStream.range(0, AMOUNT_OF_FAKE_USERS)
              .mapToObj(
                  i ->
                      UserEntity.builder()
                          .email(mockEmails.get(i))
                          .displayName(mockDisplayNames.get(i))
                          .build())
              .collect(Collectors.toList()));

  @BeforeEach
  void setup() {
    // Arrange
    mockUsers.forEach((user) -> userService.save(userMapper.toSaveBoardRequestDTO(user)));
  }

  @AfterEach
  void cleanup() {
    deleteAllUsers();
  }

  void deleteAllUsers() {
    userService.findAll().forEach((user) -> userService.deleteById(user.getId()));
  }

  @Test
  void testFindAll_shouldReturnUser_whenUserExists() {
    // Arrange
    var firstUser = userService.findAll().getFirst();

    // Act
    var foundUser = userService.findById(firstUser.getId());

    // Assert
    Assertions.assertThat(foundUser).isNotEmpty();
    Assertions.assertThat(foundUser.get().getId()).isEqualTo(firstUser.getId());
    Assertions.assertThat(foundUser.get().getDisplayName()).isEqualTo(firstUser.getDisplayName());
    Assertions.assertThat(foundUser.get().getEmail()).isEqualTo(firstUser.getEmail());
  }

  @Test
  void testFindAll_shouldReturnEmpty_whenUserDoesntExists() {
    // Arrange
    var randomUUID = UUID.randomUUID().toString();

    // Act
    var emptyUser = userService.findById(randomUUID);

    // Assert
    Assertions.assertThat(emptyUser).isEmpty();
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
  void testDeleteById_shouldReturnFalse_whenBoardNotFound() {
    // Arrange
    var firstUser = userService.findAll().getFirst();
    deleteAllUsers();

    // Act
    var wasUserDeleted = userService.deleteById(firstUser.getId());

    // Assert
    Assertions.assertThat(wasUserDeleted).isFalse();
  }

  @Test
  void testDeleteById_shouldReturnTrue_whenBoardFoundAndDeleted() {
    // Arrange
    var firstUser = userService.findAll().getFirst();

    // Act
    var wasUserDeleted = userService.deleteById(firstUser.getId());

    // Assert
    Assertions.assertThat(wasUserDeleted).isTrue();
  }
}

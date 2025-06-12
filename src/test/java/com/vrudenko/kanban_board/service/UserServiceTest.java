package com.vrudenko.kanban_board.service;

import com.vrudenko.kanban_board.AbstractAppTest;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.exception.AppEntityNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class UserServiceTest extends AbstractAppTest {
    final int AMOUNT_OF_FAKE_USERS = 5;

    @Autowired UserService userService;

    @Autowired BoardService boardService;

    List<UserResponseDTO> mockUsers = new ArrayList<>();

    @BeforeEach
    void setup() {
        for (var i = 0; i < AMOUNT_OF_FAKE_USERS; i++) {
            mockUsers.add(createUser());
        }
    }

    @Nested
    class FindAll {
        @Test
        void shouldReturnUser_whenUserExists() {
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
        void shouldReturnEmpty_whenUserDoesntExists() {
            // Arrange
            var randomUUID = UUID.randomUUID().toString();

            // Act
            var exception = Assertions.catchException(() -> userService.findById(randomUUID));

            // Assert
            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
            Assertions.assertThat(
                            userService.findAll().stream()
                                    .noneMatch(user -> user.getId().equals(randomUUID)))
                    .isTrue();
        }
    }

    @Nested
    class DeleteById {
        @Test
        void shouldDeleteUser_whenUserExists() {
            // Arrange
            var usersCountBeforeDeletion = userService.findAll().size();

            // Act
            var firstUser = userService.findAll().getFirst();
            userService.deleteById(firstUser.getId());

            // Assert
            var usersCountAfterDeletion = userService.findAll().size();
            Assertions.assertThat(usersCountAfterDeletion).isEqualTo(usersCountBeforeDeletion - 1);
            Assertions.assertThat(
                            Assertions.catchException(
                                    () -> userService.findById(firstUser.getId())))
                    .isInstanceOf(AppEntityNotFoundException.class);
        }

        @Test
        void shouldDeleteAllUsers_whenUsersExists() {
            // Arrange

            // Act
            userService.findAll().forEach(user -> userService.deleteById(user.getId()));

            // Assert
            var users = userService.findAll();
            Assertions.assertThat(users.size()).isZero();
        }

        @Test
        void shouldReturnFalse_whenUserNotFound() {
            // Arrange
            var firstUser = userService.findAll().getFirst();
            userService.deleteAll();

            // Act
            var wasUserDeleted = userService.deleteById(firstUser.getId());

            // Assert
            Assertions.assertThat(wasUserDeleted).isFalse();
        }

        @Test
        void shouldReturnTrue_whenUserFoundAndDeleted() {
            // Arrange
            var firstUser = userService.findAll().getFirst();

            // Act
            var wasUserDeleted = userService.deleteById(firstUser.getId());

            // Assert
            Assertions.assertThat(wasUserDeleted).isTrue();
        }
    }

    @Nested
    class AddBoardByUserId {
        @Test
        void shouldAddBoard_whenUserExists() {
            // Arrange
            var userId = getOwningUser().getId();
            var boardCountForUserBeforeAddition = boardService.findAllByUserId(userId).size();
            var name = dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 2);

            // Act
            var board =
                    userService.addBoardByUserId(
                            userId, SaveBoardRequestDTO.builder().name(name).build());

            // Assert
            var boardCountForUserAfterAddition = boardService.findAllByUserId(userId).size();

            Assertions.assertThat(boardCountForUserAfterAddition)
                    .isEqualTo(boardCountForUserBeforeAddition + 1);
            Assertions.assertThat(board.getName()).isEqualTo(name);
        }

        @Test
        void shouldThrow_whenUserDoesntExist() {
            // Arrange
            var userId = UUID.randomUUID().toString();
            var boardCountForUserBeforeAddition = boardService.findAllByUserId(userId).size();
            var name = dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 2);

            // Act
            var exception =
                    Assertions.catchException(
                            () ->
                                    userService.addBoardByUserId(
                                            userId,
                                            SaveBoardRequestDTO.builder().name(name).build()));

            // Assert
            var boardCountForUserAfterAddition = boardService.findAllByUserId(userId).size();

            Assertions.assertThat(exception).isInstanceOf(AppEntityNotFoundException.class);
            Assertions.assertThat(boardCountForUserAfterAddition)
                    .isEqualTo(boardCountForUserBeforeAddition);
        }
    }
}

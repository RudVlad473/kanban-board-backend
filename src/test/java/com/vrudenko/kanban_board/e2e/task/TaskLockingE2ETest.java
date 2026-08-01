package com.vrudenko.kanban_board.e2e.task;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.AbstractAppE2ETest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.task_dto.TaskResponseDTO;
import com.vrudenko.kanban_board.dto.task_dto.UpdateTaskRequestDTO;
import io.restassured.http.ContentType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class TaskLockingE2ETest extends AbstractAppE2ETest {

    private String getTaskUrl(String boardId, String columnId, String taskId) {
        return ApiPaths.BOARDS
                + "/"
                + boardId
                + ApiPaths.COLUMNS
                + "/"
                + columnId
                + ApiPaths.TASKS
                + "/"
                + taskId;
    }

    @Test
    void concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict() {
        // Arrange
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var taskId = mockPopulatedTask.getId();
        var url = getTaskUrl(boardId, columnId, taskId);
        var startingVersion = mockPopulatedTask.getVersion();

        var firstUpdate =
                UpdateTaskRequestDTO.builder()
                        .title("First writer wins")
                        .version(startingVersion)
                        .build();

        var secondUpdate =
                UpdateTaskRequestDTO.builder()
                        .title("Second writer loses")
                        .version(startingVersion)
                        .build();

        // Act: first PUT with the starting version succeeds and bumps the version
        var firstResponse =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .contentType(ContentType.JSON)
                        .body(firstUpdate)
                        .when()
                        .put(url)
                        .then()
                        .extract();

        // Assert
        Assertions.assertThat(firstResponse.statusCode()).isEqualTo(HttpStatus.OK.value());
        var firstResponseBody = firstResponse.as(TaskResponseDTO.class);
        Assertions.assertThat(firstResponseBody.getVersion()).isNotEqualTo(startingVersion);

        // Act: second PUT still holding the stale starting version is rejected
        var secondResponse =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .contentType(ContentType.JSON)
                        .body(secondUpdate)
                        .when()
                        .put(url)
                        .then()
                        .extract();

        // Assert
        Assertions.assertThat(secondResponse.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());

        // Act: re-submitting the same stale PUT again (without refetching) must still be
        // rejected, never silently succeed
        var retryResponse =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .contentType(ContentType.JSON)
                        .body(secondUpdate)
                        .when()
                        .put(url)
                        .then()
                        .extract();

        // Assert
        Assertions.assertThat(retryResponse.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void update_withCurrentVersion_succeedsAndReturnsIncrementedVersion() {
        // Arrange
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var taskId = mockPopulatedTask.getId();
        var url = getTaskUrl(boardId, columnId, taskId);
        var startingVersion = mockPopulatedTask.getVersion();

        var updateDto =
                UpdateTaskRequestDTO.builder()
                        .title("Updated with current version")
                        .version(startingVersion)
                        .build();

        // Act
        var response =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .contentType(ContentType.JSON)
                        .body(updateDto)
                        .when()
                        .put(url)
                        .then()
                        .extract();

        // Assert
        Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        var responseBody = response.as(TaskResponseDTO.class);
        Assertions.assertThat(responseBody.getVersion()).isGreaterThan(startingVersion);
    }

    @Test
    void update_withoutVersion_returnsBadRequest() {
        // Arrange
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var taskId = mockPopulatedTask.getId();
        var url = getTaskUrl(boardId, columnId, taskId);

        var updateDtoWithoutVersion =
                UpdateTaskRequestDTO.builder().title("No version here").build();

        // Act
        var response =
                given().cookie(cookie.getFirst(), cookie.getSecond())
                        .contentType(ContentType.JSON)
                        .body(updateDtoWithoutVersion)
                        .when()
                        .put(url)
                        .then()
                        .extract();

        // Assert
        Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
    }
}

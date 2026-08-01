package com.vrudenko.kanban_board.e2e.column;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.AbstractAppE2ETest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.column_dto.ColumnResponseDTO;
import com.vrudenko.kanban_board.dto.column_dto.UpdateColumnRequestDTO;
import io.restassured.http.ContentType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ColumnLockingE2ETest extends AbstractAppE2ETest {

    private String getColumnUrl(String boardId, String columnId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.COLUMNS + "/" + columnId;
    }

    @Test
    void concurrentConflictingUpdates_firstSucceeds_secondReturnsConflict() {
        // Arrange
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var url = getColumnUrl(boardId, columnId);
        var startingVersion = mockPopulatedColumn.getVersion();

        var firstUpdate =
                UpdateColumnRequestDTO.builder()
                        .name("First writer wins")
                        .version(startingVersion)
                        .build();

        var secondUpdate =
                UpdateColumnRequestDTO.builder()
                        .name("Second writer loses")
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
        Assertions.assertThat(firstResponse.statusCode()).isEqualTo(200);
        var firstResponseBody = firstResponse.as(ColumnResponseDTO.class);
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
        Assertions.assertThat(secondResponse.statusCode()).isEqualTo(409);

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
        Assertions.assertThat(retryResponse.statusCode()).isEqualTo(409);
    }

    @Test
    void update_withCurrentVersion_succeedsAndReturnsIncrementedVersion() {
        // Arrange
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var url = getColumnUrl(boardId, columnId);
        var startingVersion = mockPopulatedColumn.getVersion();

        var updateDto =
                UpdateColumnRequestDTO.builder()
                        .name("Updated with current version")
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
        Assertions.assertThat(response.statusCode()).isEqualTo(200);
        var responseBody = response.as(ColumnResponseDTO.class);
        Assertions.assertThat(responseBody.getVersion()).isGreaterThan(startingVersion);
    }

    @Test
    void update_withoutVersion_returnsBadRequest() {
        // Arrange
        Pair<String, String> cookie = signin();
        var boardId = mockPopulatedBoard.getId();
        var columnId = mockPopulatedColumn.getId();
        var url = getColumnUrl(boardId, columnId);

        var updateDtoWithoutVersion =
                UpdateColumnRequestDTO.builder().name("No version here").build();

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
        Assertions.assertThat(response.statusCode()).isEqualTo(400);
    }
}

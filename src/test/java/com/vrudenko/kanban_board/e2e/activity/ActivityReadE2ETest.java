package com.vrudenko.kanban_board.e2e.activity;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.AbstractAppE2ETest;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.service.UserService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * Real-HTTP proof of {@code GET /boards/{boardId}/activity} (READ-01, READ-02). Rows are seeded
 * directly through {@link ActivityLogRepository} rather than published through Kafka: this suite
 * needs no broker, and direct seeding is the only way to place two rows at an identical {@code
 * createdAt} instant, which the page-boundary case requires. The Kafka path itself is already
 * proven end-to-end by Plans 01 and 02.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ActivityReadE2ETest extends AbstractAppE2ETest {

    @Autowired private ActivityLogRepository activityLogRepository;

    @Autowired private UserService userService;

    private String activityUrl(String boardId) {
        return ApiPaths.BOARDS + "/" + boardId + ApiPaths.ACTIVITY;
    }

    private ActivityLogEntity seedRow(String boardId, String userId, Instant createdAt) {
        var entity = new ActivityLogEntity();
        entity.setBoardId(boardId);
        entity.setUserId(userId);
        entity.setAction(ActivityAction.TASK_CREATED);
        entity.setDetail("{}");
        entity.setEventId(UUID.randomUUID().toString());
        entity.setCreatedAt(createdAt);
        return activityLogRepository.save(entity);
    }

    @Nested
    class FindAllByBoardIdTest {

        @Test
        void shouldReturnOnlyOwnedBoardRows_whenBoardHasActivity() {
            // arrange
            var cookie = signin();
            var boardId = mockPopulatedBoard.getId();
            var otherBoardId = mockEmptyBoards.get(0).getId();
            var userId = getOwningUser().getId();

            var now = Instant.now();
            var seeded =
                    IntStream.range(0, 3)
                            .mapToObj(i -> seedRow(boardId, userId, now.plusSeconds(i)))
                            .toList();
            var otherBoardRow = seedRow(otherBoardId, userId, now);

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(activityUrl(boardId))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());

            var content = response.jsonPath().getList("content");
            Assertions.assertThat(content).hasSize(3);

            var returnedEventIds = response.jsonPath().getList("content.eventId", String.class);
            Assertions.assertThat(returnedEventIds)
                    .containsExactlyInAnyOrderElementsOf(
                            seeded.stream().map(row -> row.getEventId().toString()).toList());
            Assertions.assertThat(returnedEventIds)
                    .doesNotContain(otherBoardRow.getEventId().toString());

            var body = response.asString();
            Assertions.assertThat(body).doesNotContain("\"id\":").doesNotContain("\"boardId\":");
            for (var item : content) {
                @SuppressWarnings("unchecked")
                var itemMap = (java.util.Map<String, Object>) item;
                Assertions.assertThat(itemMap)
                        .containsKeys("eventId", "action", "detail", "userId", "createdAt");
            }
        }

        @Test
        void shouldReturnNewestFirst_whenRowsHaveDistinctTimestamps() {
            // arrange
            var cookie = signin();
            var boardId = mockPopulatedBoard.getId();
            var userId = getOwningUser().getId();

            var base = Instant.now();
            IntStream.range(0, 5).forEach(i -> seedRow(boardId, userId, base.plusSeconds(i)));

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(activityUrl(boardId))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var createdAts = response.jsonPath().getList("content.createdAt", String.class);
            var parsed = createdAts.stream().map(Instant::parse).toList();
            Assertions.assertThat(parsed).isSortedAccordingTo((a, b) -> b.compareTo(a));
        }

        @Test
        void shouldRejectAnotherUsersBoard_andNotFoundUnknownBoard() {
            // arrange
            var cookie = signin();
            var otherUser =
                    createUser(dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH));
            var otherBoard =
                    userService.addBoardByUserId(
                            otherUser.getId(),
                            SaveBoardRequestDTO.builder()
                                    .name(
                                            dataFactory.getRandomWord(
                                                    ValidationConstants.MIN_BOARD_NAME_LENGTH + 4))
                                    .build());
            var seededRow = seedRow(otherBoard.getId(), otherUser.getId(), Instant.now());

            // act
            var unauthorizedResponse =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(activityUrl(otherBoard.getId()))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(unauthorizedResponse.statusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(unauthorizedResponse.asString())
                    .doesNotContain(seededRow.getEventId().toString());

            // act
            var unknownBoardId = UUID.randomUUID().toString();
            var notFoundResponse =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(activityUrl(unknownBoardId))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(notFoundResponse.statusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }

        @Test
        void shouldReturnEveryRowExactlyOnce_whenManyRowsShareTheSameInstant() {
            // arrange
            var cookie = signin();
            var boardId = mockPopulatedBoard.getId();
            var userId = getOwningUser().getId();

            var sameInstant = Instant.now().truncatedTo(ChronoUnit.MICROS);
            var seededEventIds =
                    IntStream.range(0, 7)
                            .mapToObj(
                                    i ->
                                            seedRow(boardId, userId, sameInstant)
                                                    .getEventId()
                                                    .toString())
                            .toList();

            var pageSize = 3;
            var collectedEventIds = new ArrayList<String>();
            var page = 0;
            long totalElements = -1;
            var totalPages = -1;
            do {
                var response =
                        given().cookie(cookie.getFirst(), cookie.getSecond())
                                .queryParam("page", page)
                                .queryParam("size", pageSize)
                                .when()
                                .get(activityUrl(boardId))
                                .then()
                                .extract();

                Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
                totalElements = response.jsonPath().getLong("totalElements");
                totalPages = response.jsonPath().getInt("totalPages");
                var pageEventIds = response.jsonPath().getList("content.eventId", String.class);
                collectedEventIds.addAll(pageEventIds);
                page++;
            } while (page < totalPages);

            // assert
            Assertions.assertThat(collectedEventIds).doesNotHaveDuplicates();
            Assertions.assertThat(collectedEventIds)
                    .containsExactlyInAnyOrderElementsOf(seededEventIds);
            Assertions.assertThat(totalElements).isEqualTo(seededEventIds.size());
            Assertions.assertThat(totalPages).isEqualTo(3);
        }

        @Test
        void shouldReturnEmptyPage_whenBoardHasNoActivity() {
            // arrange
            var cookie = signin();
            var emptyBoardId = mockEmptyBoards.get(1).getId();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(activityUrl(emptyBoardId))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(response.jsonPath().getList("content")).isEmpty();
            Assertions.assertThat(response.jsonPath().getLong("totalElements")).isZero();
            Assertions.assertThat(response.jsonPath().getInt("totalPages")).isZero();
        }

        @Test
        void shouldClampPageSize_whenRequestedSizeExceedsConfiguredMaximum() {
            // arrange
            var cookie = signin();
            var boardId = mockPopulatedBoard.getId();
            var userId = getOwningUser().getId();
            seedRow(boardId, userId, Instant.now());

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .queryParam("size", 99999)
                            .when()
                            .get(activityUrl(boardId))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            Assertions.assertThat(response.jsonPath().getInt("pageable.pageSize")).isEqualTo(100);
        }

        @Test
        void shouldIgnoreCallerSuppliedSort_andStayNewestFirst() {
            // arrange
            var cookie = signin();
            var boardId = mockPopulatedBoard.getId();
            var userId = getOwningUser().getId();

            var base = Instant.now();
            IntStream.range(0, 5).forEach(i -> seedRow(boardId, userId, base.plusSeconds(i)));

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .queryParam("sort", "userId,asc")
                            .when()
                            .get(activityUrl(boardId))
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var createdAts = response.jsonPath().getList("content.createdAt", String.class);
            var parsed = createdAts.stream().map(Instant::parse).toList();
            Assertions.assertThat(parsed).isSortedAccordingTo((a, b) -> b.compareTo(a));
        }
    }
}

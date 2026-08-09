package com.vrudenko.kanban_board.e2e.activity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.entity.ActivityAction;
import com.vrudenko.kanban_board.entity.ActivityLogEntity;
import com.vrudenko.kanban_board.repository.ActivityLogRepository;
import com.vrudenko.kanban_board.service.UserService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proof of {@code GET /boards/{boardId}/activity} (READ-01, READ-02). Rows are seeded directly
 * through {@link ActivityLogRepository} rather than published through Kafka: this suite needs no
 * broker, and direct seeding is the only way to place two rows at an identical {@code createdAt}
 * instant, which the page-boundary case requires. The Kafka path itself is already proven
 * end-to-end by Plans 01 and 02.
 *
 * <p>Downgraded to the in-process MockMvc tier (D-03, verdict-table row 16). The direct repository
 * seeding above is preserved exactly -- nothing in this conversion introduces a Kafka container
 * ancestor or any broker-related configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ActivityReadE2ETest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

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

    private JsonNode readBody(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString());
    }

    private List<String> extractEventIds(JsonNode body) {
        var eventIds = new ArrayList<String>();
        body.get("content").forEach(item -> eventIds.add(item.get("eventId").asText()));
        return eventIds;
    }

    private List<Instant> extractCreatedAts(JsonNode body) {
        var createdAts = new ArrayList<Instant>();
        body.get("content")
                .forEach(item -> createdAts.add(Instant.parse(item.get("createdAt").asText())));
        return createdAts;
    }

    @Nested
    class FindAllByBoardIdTest {

        @Test
        void shouldReturnOnlyOwnedBoardRows_whenBoardHasActivity() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
                    mockMvc.perform(get(activityUrl(boardId)).cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());

            var body = readBody(response);
            Assertions.assertThat(body.get("content")).hasSize(3);

            var returnedEventIds = extractEventIds(body);
            Assertions.assertThat(returnedEventIds)
                    .containsExactlyInAnyOrderElementsOf(
                            seeded.stream().map(row -> row.getEventId().toString()).toList());
            Assertions.assertThat(returnedEventIds)
                    .doesNotContain(otherBoardRow.getEventId().toString());

            var responseBody = response.getContentAsString();
            Assertions.assertThat(responseBody)
                    .doesNotContain("\"id\":")
                    .doesNotContain("\"boardId\":");
            for (var item : body.get("content")) {
                Assertions.assertThat(item.has("eventId")).isTrue();
                Assertions.assertThat(item.has("action")).isTrue();
                Assertions.assertThat(item.has("detail")).isTrue();
                Assertions.assertThat(item.has("userId")).isTrue();
                Assertions.assertThat(item.has("createdAt")).isTrue();
            }
        }

        @Test
        void shouldReturnNewestFirst_whenRowsHaveDistinctTimestamps() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var userId = getOwningUser().getId();

            var base = Instant.now();
            IntStream.range(0, 5).forEach(i -> seedRow(boardId, userId, base.plusSeconds(i)));

            // act
            var response =
                    mockMvc.perform(get(activityUrl(boardId)).cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            var parsed = extractCreatedAts(readBody(response));
            Assertions.assertThat(parsed).isSortedAccordingTo((a, b) -> b.compareTo(a));
        }

        @Test
        void shouldRejectAnotherUsersBoard_andNotFoundUnknownBoard() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
                    mockMvc.perform(get(activityUrl(otherBoard.getId())).cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(unauthorizedResponse.getStatus())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(unauthorizedResponse.getContentAsString())
                    .doesNotContain(seededRow.getEventId().toString());

            // act
            var unknownBoardId = UUID.randomUUID().toString();
            var notFoundResponse =
                    mockMvc.perform(get(activityUrl(unknownBoardId)).cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(notFoundResponse.getStatus())
                    .isEqualTo(HttpStatus.NOT_FOUND.value());
        }

        @Test
        void shouldReturnEveryRowExactlyOnce_whenManyRowsShareTheSameInstant() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
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
                        mockMvc.perform(
                                        get(activityUrl(boardId))
                                                .cookie(cookie)
                                                .queryParam("page", String.valueOf(page))
                                                .queryParam("size", String.valueOf(pageSize)))
                                .andReturn()
                                .getResponse();

                Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
                var body = readBody(response);
                totalElements = body.get("totalElements").asLong();
                totalPages = body.get("totalPages").asInt();
                collectedEventIds.addAll(extractEventIds(body));
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
        void shouldReturnEmptyPage_whenBoardHasNoActivity() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var emptyBoardId = mockEmptyBoards.get(1).getId();

            // act
            var response =
                    mockMvc.perform(get(activityUrl(emptyBoardId)).cookie(cookie))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            var body = readBody(response);
            Assertions.assertThat(body.get("content")).isEmpty();
            Assertions.assertThat(body.get("totalElements").asLong()).isZero();
            Assertions.assertThat(body.get("totalPages").asInt()).isZero();
        }

        @Test
        void shouldClampPageSize_whenRequestedSizeExceedsConfiguredMaximum() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var userId = getOwningUser().getId();
            seedRow(boardId, userId, Instant.now());

            // act
            var response =
                    mockMvc.perform(
                                    get(activityUrl(boardId))
                                            .cookie(cookie)
                                            .queryParam("size", "99999"))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            var body = readBody(response);
            Assertions.assertThat(body.get("pageable").get("pageSize").asInt()).isEqualTo(100);
        }

        @Test
        void shouldIgnoreCallerSuppliedSort_andStayNewestFirst() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var boardId = mockPopulatedBoard.getId();
            var userId = getOwningUser().getId();

            var base = Instant.now();
            IntStream.range(0, 5).forEach(i -> seedRow(boardId, userId, base.plusSeconds(i)));

            // act
            var response =
                    mockMvc.perform(
                                    get(activityUrl(boardId))
                                            .cookie(cookie)
                                            .queryParam("sort", "userId,asc"))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            var parsed = extractCreatedAts(readBody(response));
            Assertions.assertThat(parsed).isSortedAccordingTo((a, b) -> b.compareTo(a));
        }
    }
}

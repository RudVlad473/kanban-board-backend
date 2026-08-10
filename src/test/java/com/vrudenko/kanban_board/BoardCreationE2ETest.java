package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.dto.board_dto.UpdateBoardRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.service.BoardService;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;
import io.restassured.http.ContentType;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

/**
 * Tracer proving GAP-01 end to end: a POST to the boards collection route runs through the
 * controller, DTO validation, {@link
 * com.vrudenko.kanban_board.service.UserService#addBoardByUserId}, and back out through {@link
 * com.vrudenko.kanban_board.handler.GlobalExceptionHandler}. Modeled on {@link
 * SubtaskLockingE2ETest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("realSocket")
public class BoardCreationE2ETest extends AbstractAppE2ETest {

    @Autowired private BoardService boardService;

    private String randomBoardName() {
        return dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4);
    }

    /**
     * Signs in as an arbitrary user (not necessarily {@link #getOwningUser()}), mirroring {@link
     * AbstractAppE2ETest#signin()}'s shape. Needed for the cross-user isolation case, which must
     * drive requests as two distinct signed-in users.
     */
    private Pair<String, String> signinAs(String email, String password) {
        var cookie =
                given().contentType(ContentType.JSON)
                        .body(SigninRequestDTO.builder().email(email).password(password).build())
                        .when()
                        .post(ApiPaths.SIGNIN)
                        .then()
                        .extract()
                        .cookie(COOKIE_NAME);

        return Pair.of(COOKIE_NAME, cookie);
    }

    @Nested
    class CreateBoard {
        @Test
        void shouldReturnCreatedWithLocationHeaderAndBody_whenNameIsValid() {
            // arrange
            Pair<String, String> cookie = signin();
            var boardName = randomBoardName();
            var dto = SaveBoardRequestDTO.builder().name(boardName).build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(dto)
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.CREATED.value());
            Assertions.assertThat(response.header("Location")).isNotBlank();
            var body = response.as(BoardResponseDTO.class);
            Assertions.assertThat(body.getId()).isNotBlank();
            Assertions.assertThat(body.getName()).isEqualTo(boardName);
        }

        @Test
        void shouldAppearInSubsequentGet_whenBoardCreated() {
            // arrange
            Pair<String, String> cookie = signin();
            var boardName = randomBoardName();
            var dto = SaveBoardRequestDTO.builder().name(boardName).build();

            // act
            given().cookie(cookie.getFirst(), cookie.getSecond())
                    .contentType(ContentType.JSON)
                    .body(dto)
                    .when()
                    .post(ApiPaths.BOARDS)
                    .then()
                    .statusCode(HttpStatus.CREATED.value());

            var boards =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(ApiPaths.BOARDS)
                            .then()
                            .extract()
                            .as(BoardResponseDTO[].class);

            // assert
            Assertions.assertThat(
                            Arrays.stream(boards).anyMatch(b -> b.getName().equals(boardName)))
                    .isTrue();
        }

        @Test
        void shouldReturnBadRequest_whenNameIsBlank() {
            // arrange
            Pair<String, String> cookie = signin();
            var dto = SaveBoardRequestDTO.builder().name("").build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(dto)
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnUnauthorizedAndCreateNoRow_whenNotAuthenticated() {
            // arrange -- no session cookie at all (not merely a wrong user).
            // ProblemDetailAuthenticationEntryPoint (plan 07.1-03) now produces a real 401 for
            // this case, wired explicitly via SecurityConfiguration's
            // http.exceptionHandling(...) DSL call; 403 is reserved for an
            // authenticated-but-forbidden ownership denial (D-04, D-05).
            var dto = SaveBoardRequestDTO.builder().name(randomBoardName()).build();
            var boardCountBefore = boardService.findAll().size();

            // act
            var response =
                    given().contentType(ContentType.JSON)
                            .body(dto)
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract();

            // assert: the real-socket tier proves the entry point's envelope reaches a genuine
            // HTTP client, not just MockMvc's in-process dispatch
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
            Assertions.assertThat(response.body().jsonPath().getString("code"))
                    .isEqualTo("UNAUTHENTICATED");
            Assertions.assertThat(boardService.findAll().size()).isEqualTo(boardCountBefore);
        }
    }

    @Nested
    class DuplicateName {
        @Test
        void
                shouldReturnConflictAndLeaveCountUnchanged_whenCreatingBoardWithNameAlreadyUsedBySameUser() {
            // arrange
            Pair<String, String> cookie = signin();
            var boardName = randomBoardName();
            var dto = SaveBoardRequestDTO.builder().name(boardName).build();

            given().cookie(cookie.getFirst(), cookie.getSecond())
                    .contentType(ContentType.JSON)
                    .body(dto)
                    .when()
                    .post(ApiPaths.BOARDS)
                    .then()
                    .statusCode(HttpStatus.CREATED.value());

            var boardCountAfterFirstCreate =
                    boardService.findAllByUserId(getOwningUser().getId()).size();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(dto)
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());
            Assertions.assertThat(boardService.findAllByUserId(getOwningUser().getId()).size())
                    .isEqualTo(boardCountAfterFirstCreate);
        }
    }

    @Nested
    class RenameBoard {
        @Test
        void
                shouldReturnConflictAndLeaveNamesUnchanged_whenRenamingToNameAlreadyUsedByAnotherBoardOfSameUser() {
            // arrange
            Pair<String, String> cookie = signin();
            var nameA = randomBoardName();
            var nameB = randomBoardName();

            var boardA =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(SaveBoardRequestDTO.builder().name(nameA).build())
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract()
                            .as(BoardResponseDTO.class);
            var boardB =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(SaveBoardRequestDTO.builder().name(nameB).build())
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract()
                            .as(BoardResponseDTO.class);

            // act: rename B to A's name
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(
                                    UpdateBoardRequestDTO.builder()
                                            .name(nameA)
                                            .version(boardB.getVersion())
                                            .build())
                            .when()
                            .put(ApiPaths.BOARDS + "/" + boardB.getId())
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.CONFLICT.value());

            var boards =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(ApiPaths.BOARDS)
                            .then()
                            .extract()
                            .as(BoardResponseDTO[].class);
            var reloadedA =
                    Arrays.stream(boards)
                            .filter(b -> b.getId().equals(boardA.getId()))
                            .toList()
                            .getFirst();
            var reloadedB =
                    Arrays.stream(boards)
                            .filter(b -> b.getId().equals(boardB.getId()))
                            .toList()
                            .getFirst();

            Assertions.assertThat(reloadedA.getName()).isEqualTo(nameA);
            Assertions.assertThat(reloadedB.getName()).isEqualTo(nameB);
        }

        @Test
        void shouldReturnOk_whenRenamingBoardToItsOwnCurrentName() {
            // arrange
            Pair<String, String> cookie = signin();
            var boardName = randomBoardName();

            var board =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(SaveBoardRequestDTO.builder().name(boardName).build())
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract()
                            .as(BoardResponseDTO.class);

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(
                                    UpdateBoardRequestDTO.builder()
                                            .name(boardName)
                                            .version(board.getVersion())
                                            .build())
                            .when()
                            .put(ApiPaths.BOARDS + "/" + board.getId())
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
        }
    }

    @Nested
    class CrossUserIsolation {
        @Test
        void shouldAllowBothCreates_whenTwoDifferentUsersUseIdenticalBoardName() {
            // arrange
            var boardName = randomBoardName();
            // generateValidPassword() (not a raw dataFactory word) because this password is
            // posted to the real POST /signin route below via signinAs(), which validates its
            // body as of D-06.
            var otherPassword = generateValidPassword();
            var otherUser = createUser(otherPassword);

            Pair<String, String> ownerCookie = signin();
            Pair<String, String> otherCookie = signinAs(otherUser.getEmail(), otherPassword);

            // act
            var ownerResponse =
                    given().cookie(ownerCookie.getFirst(), ownerCookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(SaveBoardRequestDTO.builder().name(boardName).build())
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract();
            var otherResponse =
                    given().cookie(otherCookie.getFirst(), otherCookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(SaveBoardRequestDTO.builder().name(boardName).build())
                            .when()
                            .post(ApiPaths.BOARDS)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(ownerResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());
            Assertions.assertThat(otherResponse.statusCode()).isEqualTo(HttpStatus.CREATED.value());

            var ownerBoards =
                    given().cookie(ownerCookie.getFirst(), ownerCookie.getSecond())
                            .when()
                            .get(ApiPaths.BOARDS)
                            .then()
                            .extract()
                            .as(BoardResponseDTO[].class);
            var otherBoards =
                    given().cookie(otherCookie.getFirst(), otherCookie.getSecond())
                            .when()
                            .get(ApiPaths.BOARDS)
                            .then()
                            .extract()
                            .as(BoardResponseDTO[].class);

            Assertions.assertThat(
                            Arrays.stream(ownerBoards)
                                    .filter(b -> b.getName().equals(boardName))
                                    .count())
                    .isEqualTo(1);
            Assertions.assertThat(
                            Arrays.stream(otherBoards)
                                    .filter(b -> b.getName().equals(boardName))
                                    .count())
                    .isEqualTo(1);
        }
    }

    @Nested
    class ConcurrentCreate {
        @Test
        void shouldPersistExactlyOneBoard_whenTwoRequestsCreateSameNameConcurrently()
                throws InterruptedException {
            // arrange -- mirrors ActivityLogIdempotencyE2ETest.ConcurrentRecordTest's structure:
            // two threads race through the same service-level check-then-act window, backstopped
            // by uk_boards_user_id_name (plan 01's V5). The assertion is on the final row count,
            // never on which caller "won" -- under a real race either request can receive the
            // 409, and asserting on the loser's identity would make this test flaky by
            // construction.
            Pair<String, String> cookie = signin();
            var boardName = randomBoardName();
            var dto = SaveBoardRequestDTO.builder().name(boardName).build();

            var startGate = new CountDownLatch(1);
            var firstStatus = new AtomicReference<Integer>();
            var secondStatus = new AtomicReference<Integer>();
            ExecutorService executor = Executors.newFixedThreadPool(2);

            // act
            try {
                // The returned Futures are deliberately dropped, not awaited -- awaiting them
                // here would serialize the two submissions and destroy the race window this test
                // exists to open (same reasoning as ActivityLogIdempotencyE2ETest's
                // ConcurrentRecordTest).
                {
                    Future<?> unused =
                            executor.submit(
                                    () -> {
                                        try {
                                            startGate.await();
                                            var status =
                                                    given().cookie(
                                                                    cookie.getFirst(),
                                                                    cookie.getSecond())
                                                            .contentType(ContentType.JSON)
                                                            .body(dto)
                                                            .when()
                                                            .post(ApiPaths.BOARDS)
                                                            .then()
                                                            .extract()
                                                            .statusCode();
                                            firstStatus.set(status);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
                }
                {
                    Future<?> unused =
                            executor.submit(
                                    () -> {
                                        try {
                                            startGate.await();
                                            var status =
                                                    given().cookie(
                                                                    cookie.getFirst(),
                                                                    cookie.getSecond())
                                                            .contentType(ContentType.JSON)
                                                            .body(dto)
                                                            .when()
                                                            .post(ApiPaths.BOARDS)
                                                            .then()
                                                            .extract()
                                                            .statusCode();
                                            secondStatus.set(status);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                        }
                                    });
                }

                startGate.countDown();
                executor.shutdown();
                Assertions.assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            } finally {
                executor.shutdownNow();
            }

            // assert
            Assertions.assertThat(firstStatus.get()).isNotNull();
            Assertions.assertThat(secondStatus.get()).isNotNull();

            var matchingBoards =
                    boardService.findAllByUserId(getOwningUser().getId()).stream()
                            .filter(b -> b.getName().equals(boardName))
                            .toList();
            Assertions.assertThat(matchingBoards).hasSize(1);
        }
    }
}

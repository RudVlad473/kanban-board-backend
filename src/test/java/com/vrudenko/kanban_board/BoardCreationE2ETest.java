package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.board_dto.BoardResponseDTO;
import com.vrudenko.kanban_board.dto.board_dto.SaveBoardRequestDTO;
import com.vrudenko.kanban_board.service.BoardService;
import io.restassured.http.ContentType;
import java.util.Arrays;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
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
public class BoardCreationE2ETest extends AbstractAppE2ETest {

    @Autowired private BoardService boardService;

    private String randomBoardName() {
        return dataFactory.getRandomWord(ValidationConstants.MIN_BOARD_NAME_LENGTH + 4);
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
        void shouldReturnForbiddenAndCreateNoRow_whenNotAuthenticated() {
            // arrange -- no session cookie at all (not merely a wrong user). This app registers
            // no custom AuthenticationEntryPoint (no formLogin/httpBasic in
            // SecurityConfiguration), so Spring Security's default Http403ForbiddenEntryPoint
            // applies uniformly to every @PreAuthorize("isAuthenticated()") route when the
            // security context is empty -- verified against this route directly, since no
            // existing test in this codebase exercises a fully-unauthenticated (zero-cookie)
            // request.
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

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
            Assertions.assertThat(boardService.findAll().size()).isEqualTo(boardCountBefore);
        }
    }
}

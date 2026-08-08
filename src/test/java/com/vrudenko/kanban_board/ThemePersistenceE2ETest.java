package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.UpdateThemeRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.ThemePreference;
import io.restassured.http.ContentType;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.util.Pair;
import org.springframework.http.HttpStatus;

/**
 * Tracer proving GAP-05 end to end: a request travels controller ({@link
 * com.vrudenko.kanban_board.controller.UserController}) to session-resolved identity
 * ({@code @CurrentUserId}) to service ({@link com.vrudenko.kanban_board.service.UserService}) to
 * database and back, over real HTTP. Modeled on {@link SubtaskLockingE2ETest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ThemePersistenceE2ETest extends AbstractAppE2ETest {

    private static final String THEME_URL = ApiPaths.USERS + ApiPaths.ME + ApiPaths.THEME;

    @Nested
    class GetTheme {
        @Test
        void shouldReturnLight_whenUserHasNoExplicitPreference() {
            // arrange
            Pair<String, String> cookie = signin();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(THEME_URL)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var body = response.as(UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.LIGHT);
        }

        @Test
        void shouldReturnForbidden_whenNotAuthenticated() {
            // arrange
            // act
            var response = given().when().get(THEME_URL).then().extract();

            // assert: Spring Security's default Http403ForbiddenEntryPoint applies uniformly to
            // every @PreAuthorize("isAuthenticated()") route with no session cookie at all --
            // verified pre-existing, route-independent framework behavior (plan 06-02 found the
            // identical result for the fully-unauthenticated POST /boards case).
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    @Nested
    class UpdateTheme {
        @Test
        void shouldReturnOkWithDark_whenWritingDark() {
            // arrange
            Pair<String, String> cookie = signin();
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(dto)
                            .when()
                            .put(THEME_URL)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.OK.value());
            var body = response.as(UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.DARK);
        }

        @Test
        void shouldReturnDarkOnSubsequentGet_whenDarkWasJustWritten() {
            // arrange
            Pair<String, String> cookie = signin();
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act
            given().cookie(cookie.getFirst(), cookie.getSecond())
                    .contentType(ContentType.JSON)
                    .body(dto)
                    .when()
                    .put(THEME_URL);

            var getResponse =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(THEME_URL)
                            .then()
                            .extract();

            // assert
            var body = getResponse.as(UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.DARK);
        }

        @Test
        void shouldReturnBadRequestAndLeaveValueUnchanged_whenThemeIsUnknownValue() {
            // arrange
            Pair<String, String> cookie = signin();
            var invalidBody = "{\"theme\":\"NOT_A_REAL_THEME\"}";

            // act
            var putResponse =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(invalidBody)
                            .when()
                            .put(THEME_URL)
                            .then()
                            .extract();

            // assert: an unknown enum value fails Jackson deserialisation as a 400, not a 500 --
            // see GlobalExceptionHandler.handleHttpMessageNotReadable
            Assertions.assertThat(putResponse.statusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());

            // assert: the previously-stored value (the LIGHT default -- no write has succeeded
            // yet in this test) is unchanged
            var getResponse =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .when()
                            .get(THEME_URL)
                            .then()
                            .extract();
            var body = getResponse.as(UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.LIGHT);
        }

        @Test
        void shouldReturnBadRequest_whenThemeIsMissing() {
            // arrange
            Pair<String, String> cookie = signin();
            var emptyBody = "{}";

            // act
            var response =
                    given().cookie(cookie.getFirst(), cookie.getSecond())
                            .contentType(ContentType.JSON)
                            .body(emptyBody)
                            .when()
                            .put(THEME_URL)
                            .then()
                            .extract();

            // assert
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnForbidden_whenNotAuthenticated() {
            // arrange
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act
            var response =
                    given().contentType(ContentType.JSON)
                            .body(dto)
                            .when()
                            .put(THEME_URL)
                            .then()
                            .extract();

            // assert: see GetTheme.shouldReturnForbidden_whenNotAuthenticated for why 403, not 401
            Assertions.assertThat(response.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }
}

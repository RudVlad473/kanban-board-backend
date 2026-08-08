package com.vrudenko.kanban_board;

import static io.restassured.RestAssured.given;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.constant.ValidationConstants;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
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

        /**
         * The load-bearing case in this plan: it is the one test that actually distinguishes
         * server-side persistence (D-10) from a client-side or {@code HttpSession}-scoped
         * preference. A test that only PUTs then GETs within one session would pass against a
         * session-scoped implementation too and would prove nothing about the requirement -- the
         * logout + fresh signin in between is what makes this a real round trip through the {@code
         * users} table.
         */
        @Test
        void shouldReturnDark_whenLoggingOutAndSigningInAgainAfterWritingDark() {
            // arrange
            Pair<String, String> firstCookie = signin();
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act: write DARK, then log out (clears the session server-side)
            given().cookie(firstCookie.getFirst(), firstCookie.getSecond())
                    .contentType(ContentType.JSON)
                    .body(dto)
                    .when()
                    .put(THEME_URL);

            given().cookie(firstCookie.getFirst(), firstCookie.getSecond())
                    .when()
                    .post(ApiPaths.LOGOUT);

            // act: sign in again as the same user, under a brand-new session
            Pair<String, String> secondCookie = signin();
            Assertions.assertThat(secondCookie.getSecond()).isNotEqualTo(firstCookie.getSecond());

            var getResponse =
                    given().cookie(secondCookie.getFirst(), secondCookie.getSecond())
                            .when()
                            .get(THEME_URL)
                            .then()
                            .extract();

            // assert: the fresh session still sees DARK -- the value came from the users table,
            // not from the (now-cleared) first session
            var body = getResponse.as(UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.DARK);
        }

        @Test
        void shouldBeIndependentPerUser_whenTwoUsersSetDifferentThemes() {
            // arrange
            Pair<String, String> firstUserCookie = signin();

            // createUser() only exposes an unpredictable, internally-generated password, so a
            // second, independently sign-in-able user is created with an explicit password here
            // instead (AbstractAppTest.createUser(String) overload) -- still a real users row,
            // just not routed through the HTTP signup endpoint.
            var secondUserPassword =
                    dataFactory.getRandomWord(ValidationConstants.MIN_PASSWORD_LENGTH);
            var secondUser = createUser(secondUserPassword);
            var secondUserSigninCookie =
                    given().contentType(ContentType.JSON)
                            .body(
                                    SigninRequestDTO.builder()
                                            .email(secondUser.getEmail())
                                            .password(secondUserPassword)
                                            .build())
                            .when()
                            .post(ApiPaths.SIGNIN)
                            .then()
                            .extract()
                            .cookie(COOKIE_NAME);

            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act: the first (fixture-owning) user writes DARK
            given().cookie(firstUserCookie.getFirst(), firstUserCookie.getSecond())
                    .contentType(ContentType.JSON)
                    .body(dto)
                    .when()
                    .put(THEME_URL);

            // assert: the second, brand-new user still reads LIGHT -- the first user's write did
            // not leak across the row boundary
            var secondUserGetResponse =
                    given().cookie(COOKIE_NAME, secondUserSigninCookie)
                            .when()
                            .get(THEME_URL)
                            .then()
                            .extract();
            var secondUserBody = secondUserGetResponse.as(UserResponseDTO.class);
            Assertions.assertThat(secondUserBody.getTheme()).isEqualTo(ThemePreference.LIGHT);

            // assert: the first user's own write is unaffected by the second user's read
            var firstUserGetResponse =
                    given().cookie(firstUserCookie.getFirst(), firstUserCookie.getSecond())
                            .when()
                            .get(THEME_URL)
                            .then()
                            .extract();
            var firstUserBody = firstUserGetResponse.as(UserResponseDTO.class);
            Assertions.assertThat(firstUserBody.getTheme()).isEqualTo(ThemePreference.DARK);
        }
    }
}

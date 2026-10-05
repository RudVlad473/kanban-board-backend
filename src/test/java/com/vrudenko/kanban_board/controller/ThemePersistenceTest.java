package com.vrudenko.kanban_board.controller;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.UpdateThemeRequestDTO;
import com.vrudenko.kanban_board.dto.user_dto.UserResponseDTO;
import com.vrudenko.kanban_board.entity.ThemePreference;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppMockMvcTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Tracer for the theme preference: controller, session-resolved identity ({@code @CurrentUserId}),
 * service and database, round trip over MockMvc.
 *
 * <p>Why this is the way it is: it uses the real-signin cookie relay ({@link
 * AbstractAppMockMvcTest#signinCookie()}), never {@code .with(user())}, because the logout round
 * trip is the proof the preference lives in the {@code users} table and not the session, and the
 * per-user isolation case needs a second genuine session.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ThemePersistenceTest extends AbstractAppMockMvcTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    private static final String THEME_URL = ApiPaths.USERS + ApiPaths.ME + ApiPaths.THEME;

    @Nested
    class GetTheme {
        @Test
        void shouldReturnLight_whenUserHasNoExplicitPreference() throws Exception {
            // arrange
            Cookie cookie = signinCookie();

            // act
            var response = mockMvc.perform(get(THEME_URL).cookie(cookie)).andReturn().getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            var body = objectMapper.readValue(response.getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.LIGHT);
        }

        @Test
        void shouldReturnUnauthorized_whenNotAuthenticated() throws Exception {
            // arrange
            // act
            var response = mockMvc.perform(get(THEME_URL)).andReturn().getResponse();

            // assert: an unauthenticated request gets 401 from
            // ProblemDetailAuthenticationEntryPoint;
            // 403 is for authenticated-but-forbidden, which this route (no ownership) never hits.
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
    }

    @Nested
    class UpdateTheme {
        @Test
        void shouldReturnOkWithDark_whenWritingDark() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act
            var response =
                    mockMvc.perform(
                                    put(THEME_URL)
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(dto)))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            var body = objectMapper.readValue(response.getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.DARK);
        }

        @Test
        void shouldReturnDarkOnSubsequentGet_whenDarkWasJustWritten() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act
            mockMvc.perform(
                    put(THEME_URL)
                            .cookie(cookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)));

            var getResponse =
                    mockMvc.perform(get(THEME_URL).cookie(cookie)).andReturn().getResponse();

            // assert
            var body =
                    objectMapper.readValue(getResponse.getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.DARK);
        }

        @Test
        void shouldReturnBadRequestAndLeaveValueUnchanged_whenThemeIsUnknownValue()
                throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var invalidBody = "{\"theme\":\"NOT_A_REAL_THEME\"}";

            // act
            var putResponse =
                    mockMvc.perform(
                                    put(THEME_URL)
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(invalidBody))
                            .andReturn()
                            .getResponse();

            // assert: an unknown enum value fails Jackson deserialisation as a 400, not a 500 --
            // see GlobalExceptionHandler.handleHttpMessageNotReadable
            Assertions.assertThat(putResponse.getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST.value());

            // assert: the previously-stored value (the LIGHT default -- no write has succeeded
            // yet in this test) is unchanged
            var getResponse =
                    mockMvc.perform(get(THEME_URL).cookie(cookie)).andReturn().getResponse();
            var body =
                    objectMapper.readValue(getResponse.getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.LIGHT);
        }

        @Test
        void shouldReturnBadRequest_whenThemeIsMissing() throws Exception {
            // arrange
            Cookie cookie = signinCookie();
            var emptyBody = "{}";

            // act
            var response =
                    mockMvc.perform(
                                    put(THEME_URL)
                                            .cookie(cookie)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(emptyBody))
                            .andReturn()
                            .getResponse();

            // assert
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        void shouldReturnUnauthorized_whenNotAuthenticated() throws Exception {
            // arrange
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act
            var response =
                    mockMvc.perform(
                                    put(THEME_URL)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(objectMapper.writeValueAsString(dto)))
                            .andReturn()
                            .getResponse();

            // assert: see GetTheme.shouldReturnUnauthorized_whenNotAuthenticated for why 401,
            // not 403
            Assertions.assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        /**
         * The load-bearing case: the one test that distinguishes server-side persistence from a
         * client-side or session-scoped preference.
         *
         * <p>A PUT then GET within one session would pass against a session-scoped implementation;
         * the logout and fresh signin in between make this a real round trip through the {@code
         * users} table.
         */
        @Test
        void shouldReturnDark_whenLoggingOutAndSigningInAgainAfterWritingDark() throws Exception {
            // arrange
            Cookie firstCookie = signinCookie();
            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act: write DARK, then log out (clears the session server-side)
            mockMvc.perform(
                    put(THEME_URL)
                            .cookie(firstCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)));

            mockMvc.perform(post(ApiPaths.LOGOUT).cookie(firstCookie));

            // act: sign in again as the same user, under a brand-new session
            Cookie secondCookie = signinCookie();
            Assertions.assertThat(secondCookie.getValue()).isNotEqualTo(firstCookie.getValue());

            var getResponse =
                    mockMvc.perform(get(THEME_URL).cookie(secondCookie)).andReturn().getResponse();

            // assert: the fresh session still sees DARK -- the value came from the users table,
            // not from the (now-cleared) first session
            var body =
                    objectMapper.readValue(getResponse.getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(body.getTheme()).isEqualTo(ThemePreference.DARK);
        }

        @Test
        void shouldBeIndependentPerUser_whenTwoUsersSetDifferentThemes() throws Exception {
            // arrange
            Cookie firstUserCookie = signinCookie();

            // createUser() exposes only an unpredictable password, so create the second user with
            // an explicit generateValidPassword() (the POST /signin body is validated): still a
            // real users row.
            var secondUserPassword = generateValidPassword();
            var secondUser = createUser(secondUserPassword);
            var secondUserCookie = signinCookie(secondUser.getEmail(), secondUserPassword);

            var dto = UpdateThemeRequestDTO.builder().theme(ThemePreference.DARK).build();

            // act: the first (fixture-owning) user writes DARK
            mockMvc.perform(
                    put(THEME_URL)
                            .cookie(firstUserCookie)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dto)));

            // assert: the second, brand-new user still reads LIGHT -- the first user's write did
            // not leak across the row boundary
            var secondUserGetResponse =
                    mockMvc.perform(get(THEME_URL).cookie(secondUserCookie))
                            .andReturn()
                            .getResponse();
            var secondUserBody =
                    objectMapper.readValue(
                            secondUserGetResponse.getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(secondUserBody.getTheme()).isEqualTo(ThemePreference.LIGHT);

            // assert: the first user's own write is unaffected by the second user's read
            var firstUserGetResponse =
                    mockMvc.perform(get(THEME_URL).cookie(firstUserCookie))
                            .andReturn()
                            .getResponse();
            var firstUserBody =
                    objectMapper.readValue(
                            firstUserGetResponse.getContentAsString(), UserResponseDTO.class);
            Assertions.assertThat(firstUserBody.getTheme()).isEqualTo(ThemePreference.DARK);
        }
    }
}

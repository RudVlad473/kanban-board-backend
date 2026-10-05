package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.dto.user_dto.SigninRequestDTO;
import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;

import io.restassured.http.ContentType;
import io.restassured.http.Cookie;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static io.restassured.RestAssured.given;

/**
 * Asserts the full published session-cookie contract against a real {@code Set-Cookie} header from
 * a genuine signin over a real socket.
 *
 * <p>Why this is the way it is: {@code AbstractAppMockMvcTest} uses no real HTTP transport, so it
 * never produces a container-serialised {@code Set-Cookie} header. With Spring Session JDBC on the
 * classpath the cookie is written by {@code DefaultCookieSerializer}, not the servlet container's
 * {@code SessionCookieConfig}, and left unset that serializer derives {@code Secure} from whether
 * the current request was secure; this test observes the explicit, unconditional attribute on the
 * wire instead of inferring it from the properties file. The {@code realSocket} tag keeps the class
 * out of the pre-commit {@code fastTest} gate (docs/CODE_STYLE.md rule 4): a real socket round trip
 * does not belong in every commit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("realSocket")
public class SessionCookieAttributesE2ETest extends AbstractAppE2ETest {

    @Value("${server.servlet.session.cookie.max-age}")
    private long expectedMaxAge;

    @Nested
    class SigninCookieAttributes {
        @Test
        void shouldCarryHardenedAttributes_whenSignedIn() {
            // arrange & act -- a fresh signin that extracts the full cookie object, attributes
            // included, since signin() only returns the value.
            Cookie cookie =
                    given().contentType(ContentType.JSON)
                            .body(
                                    SigninRequestDTO.builder()
                                            .email(getOwningUser().getEmail())
                                            .password(getOwningUserPassword())
                                            .build())
                            .when()
                            .post(ApiPaths.SIGNIN)
                            .then()
                            .extract()
                            .detailedCookie(COOKIE_NAME);

            // assert -- each attribute is its own assertion with a naming failure message, so a
            // regression on one attribute is unambiguous rather than reported as "cookie wrong".
            Assertions.assertThat(cookie)
                    .as("the session cookie must be present on the signin response")
                    .isNotNull();

            Assertions.assertThat(cookie.getName())
                    .as("cookie name must match the configured session cookie name")
                    .isEqualTo(COOKIE_NAME);

            Assertions.assertThat(cookie.isSecured())
                    .as(
                            "Secure attribute must be set on the session cookie (HARDEN-07) -- a"
                                    + " browser must never transmit it over a non-TLS connection")
                    .isTrue();

            Assertions.assertThat(cookie.isHttpOnly())
                    .as("HttpOnly attribute must be set on the session cookie")
                    .isTrue();

            Assertions.assertThat(cookie.getSameSite())
                    .as("SameSite attribute must be Strict on the session cookie")
                    .isNotNull()
                    .isEqualToIgnoringCase("Strict");

            Assertions.assertThat(cookie.getPath())
                    .as("Path attribute must be / on the session cookie")
                    .isEqualTo("/");

            Assertions.assertThat(cookie.getMaxAge())
                    .as("Max-Age attribute must match the configured session cookie max-age")
                    .isEqualTo(expectedMaxAge);
        }
    }
}

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
 * Asserts the full published session-cookie contract (HARDEN-07) against a real {@code Set-Cookie}
 * header produced by a genuine signin over the real-socket transport.
 *
 * <p><b>Why real-socket tier, not MockMvc (rule 4, {@code docs/CODE_STYLE.md}):</b> {@code
 * AbstractAppMockMvcTest} uses no real HTTP transport at all, so it never produces a
 * container-serialised {@code Set-Cookie} header and cannot observe any of these attributes. Spring
 * Session JDBC is on the classpath, so the cookie is written by Spring Session's own {@code
 * DefaultCookieSerializer} -- not directly by the embedded servlet container's {@code
 * SessionCookieConfig} -- which is what Spring Boot's session auto-configuration maps {@code
 * server.servlet.session.cookie.*} onto. Left unset, that serializer derives {@code Secure} from
 * whether the *current request* was secure, a per-request value; setting it explicitly (Task 2)
 * makes the attribute unconditional instead, which is exactly what this test has to observe on the
 * wire rather than infer from the properties file.
 *
 * <p>{@code @Tag("realSocket")} excludes this class from the pre-commit {@code fastTest} gate
 * (docs/CODE_STYLE.md rule 4's tag-based, not name-based, gate membership) -- a real socket
 * round-trip has no place slowing down every commit.
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
            // arrange & act -- a fresh signin, mirroring AbstractAppE2ETest.signin() but
            // extracting the full cookie object (attributes included) rather than just its value,
            // since signin() only ever returns the value.
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

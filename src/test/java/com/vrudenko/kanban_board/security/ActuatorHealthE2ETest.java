package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;

/**
 * Proves Phase 5's Actuator health endpoint (INFRA-01) is reachable exactly as the production
 * Docker healthcheck will curl it -- unauthenticated, over the real resolved {@code
 * /api/actuator/health} URL -- and that the exposure allowlist ({@code application.properties}'s
 * {@code management.endpoints.web.exposure.include=health}) is a real allowlist, not an accidental
 * wildcard that would also publish {@code /env}.
 *
 * <p><b>Why real-socket tier, not MockMvc:</b> extends {@link AbstractAppE2ETest} rather than the
 * MockMvc tier so this test exercises the actual embedded servlet container's context-path
 * stripping ({@code server.servlet.context-path=/api}) -- RESEARCH.md's Pattern 1 flags this exact
 * trap: a matcher that looks right in isolation can still let a manual, logged-in-browser curl
 * appear to work while Docker's own unauthenticated healthcheck request gets a 302/401. {@code
 * SessionPersistenceE2ETest}, the class this plan's task originally pointed to for conventions, was
 * merged into {@link AuthenticationTest} during Phase 7 (now MockMvc-tier); this class instead
 * follows the shape of this codebase's current {@code AbstractAppE2ETest} reference points, {@link
 * com.vrudenko.kanban_board.e2e.board.BoardCreationE2ETest} and {@link
 * ConcurrentSigninCeilingE2ETest}. Unlike those two, nothing here is concurrent or slow, so no
 * {@code @Tag("realSocket")} is applied -- this class runs in the pre-commit {@code fastTest} gate
 * by default (docs/CODE_STYLE.md rule 4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ActuatorHealthE2ETest extends AbstractAppE2ETest {

    private static final String HEALTH_PATH = "/actuator/health";
    private static final String ENV_PATH = "/actuator/env";

    @Nested
    class HealthCheckTest {
        @Test
        void shouldReturnOk_whenHealthCheckedWithoutSession() {
            // arrange & act
            var statusCode = given().when().get(HEALTH_PATH).then().extract().statusCode();

            // assert
            Assertions.assertThat(statusCode).isEqualTo(HttpStatus.OK.value());
        }

        @Test
        void shouldReportAggregateStatusUp_whenHealthCheckedWithoutSession() {
            // arrange & act
            var status =
                    given().when().get(HEALTH_PATH).then().extract().jsonPath().getString("status");

            // assert
            Assertions.assertThat(status).isEqualTo("UP");
        }

        @Test
        void shouldNotIncludeComponentDetail_whenHealthCheckedWithoutSession() {
            // arrange & act
            var body = given().when().get(HEALTH_PATH).then().extract().body().asString();

            // assert: show-details=never means no per-component detail block (datasource URL,
            // driver, validation query) ever appears in an unauthenticated response
            Assertions.assertThat(body).doesNotContain("components");
            Assertions.assertThat(body).doesNotContain("\"details\"");
        }
    }

    @Nested
    class EnvEndpointExclusionTest {
        @Test
        void shouldReturnNonSuccess_whenEnvEndpointRequestedWithoutSession() {
            // arrange & act
            var statusCode = given().when().get(ENV_PATH).then().extract().statusCode();

            // assert: the exposure allowlist is exactly `health` -- env must not be reachable,
            // proving the include list is a real allowlist rather than an accidental wildcard
            Assertions.assertThat(statusCode)
                    .isGreaterThanOrEqualTo(HttpStatus.MULTIPLE_CHOICES.value());
        }
    }
}

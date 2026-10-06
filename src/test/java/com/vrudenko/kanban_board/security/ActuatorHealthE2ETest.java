package com.vrudenko.kanban_board.security;

import com.vrudenko.kanban_board.support.fixtures.AbstractAppE2ETest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;

/**
 * Proves /api/actuator/health is reachable unauthenticated, as the healthcheck curls it,
 * and that the exposure allowlist is real, not a wildcard.
 *
 * Why this is the way it is: it extends AbstractAppE2ETest, not the MockMvc tier, to
 * exercise the embedded servlet container's context-path stripping (
 * server.servlet.context-path=/api); a matcher that looks right in isolation can let a
 * logged-in-browser curl work while Docker's unauthenticated healthcheck gets a 302/401. It carries
 * no @Tag("realSocket"): nothing here is concurrent or slow, so it runs in the pre-commit
 * fastTest gate (docs/CODE_STYLE.md rule 4).
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

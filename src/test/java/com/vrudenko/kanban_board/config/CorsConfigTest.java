package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Asserts what {@link CorsConfig#corsConfigurationSource(java.util.List)} advertises, not real
 * browser preflight behavior.
 *
 * <p>Why this is the way it is: {@code MockMvc} dispatches in-process and never builds a
 * cross-origin preflight, so only what the backend advertises is provable at this tier. Do not
 * upgrade this to a preflight test here; that needs a real-socket test (REST Assured against {@code
 * AbstractAppE2ETest}). Extends {@link AbstractPostgresContainerTest} because the test needs no
 * fixture data (docs/CODE_STYLE.md rule 4).
 */
@SpringBootTest
class CorsConfigTest extends AbstractPostgresContainerTest {

    @Autowired private CorsConfigurationSource corsConfigurationSource;

    @Nested
    class CorsConfigurationSourceTest {

        @Test
        void shouldResolveExplicitCredentialedConfiguration_whenRequestedForApiPath() {
            // arrange
            var request = new MockHttpServletRequest();
            request.setRequestURI("/api/boards");

            // act
            var configuration = corsConfigurationSource.getCorsConfiguration(request);

            // assert
            Assertions.assertThat(configuration).isNotNull();
            Assertions.assertThat(configuration.getAllowedOrigins())
                    .containsExactlyInAnyOrder("http://localhost:5173", "http://localhost:3000");
            Assertions.assertThat(configuration.getAllowedOrigins()).doesNotContain("*");
            Assertions.assertThat(configuration.getAllowedMethods())
                    .contains("GET", "POST", "PUT", "PATCH", "DELETE");
            Assertions.assertThat(configuration.getAllowCredentials()).isEqualTo(Boolean.TRUE);
        }
    }
}

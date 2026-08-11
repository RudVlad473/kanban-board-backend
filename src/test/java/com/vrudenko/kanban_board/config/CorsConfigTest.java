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
 * Asserts the {@link CorsConfig#corsConfigurationSource(java.util.List)} bean's resolved
 * configuration (D-10..D-12), not real browser preflight behavior. CORS is browser-enforced --
 * {@code MockMvc} dispatches in-process and never constructs a genuine cross-origin preflight
 * {@code OPTIONS} request, so it cannot prove what a real browser will actually allow. This class
 * instead asserts what the backend <em>advertises</em> through the resolved {@link
 * org.springframework.web.cors.CorsConfiguration} -- the strongest claim available at this tier. Do
 * not "upgrade" this to a preflight test at this tier; that would need a real-socket test (e.g.
 * REST Assured against {@code AbstractAppE2ETest}) instead.
 *
 * <p>Extends {@link AbstractPostgresContainerTest} directly, matching {@code
 * KanbanBoardApplicationTests}'s precedent, rather than {@code AbstractAppTest} -- this test needs
 * no users, boards, or any other fixture data, so inheriting {@code AbstractAppTest}'s full
 * per-test fixture build (docs/CODE_STYLE.md rule 4) would be unjustified overhead.
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

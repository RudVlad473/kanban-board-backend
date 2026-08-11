package com.vrudenko.kanban_board.config;

import com.vrudenko.kanban_board.constant.ApiPaths;
import com.vrudenko.kanban_board.support.containers.AbstractPostgresContainerTest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard for the OpenAPI document endpoint SpringDoc exposes at {@code
 * springdoc.api-docs.path}. This was a live production defect (todo
 * 2026-08-09-fix-broken-api-docs-swagger-endpoint-swagger-annotations-ver.md): {@code
 * io.confluent:kafka-avro-serializer} transitively pulled in a pre-jakarta {@code
 * swagger-annotations} artifact that shadowed the jakarta one SpringDoc 2.8.8 actually needs,
 * causing every {@code GET /api/docs} call to 500 with {@code NoSuchMethodError:
 * Parameter.validationGroups()}.
 *
 * <p>Extends {@link AbstractPostgresContainerTest} directly, matching {@code CorsConfigTest}'s
 * precedent, rather than {@code AbstractAppTest} -- this test needs no users, boards, or any other
 * fixture data, so inheriting {@code AbstractAppTest}'s full per-test fixture build
 * (docs/CODE_STYLE.md rule 4) would be unjustified overhead.
 *
 * <p>{@code MockMvc} does NOT apply {@code server.servlet.context-path} the way a real embedded
 * servlet container does (docs/CODE_STYLE.md rule 4, {@code AbstractAppMockMvcTest}'s Javadoc), so
 * this class requests the bare {@code springdoc.api-docs.path} value, while a real deployment
 * serves the same document under {@code /api}. That is a documented tier limitation, not an
 * oversight -- the live `/api/docs` path is exercised separately by the operator check in this
 * quick task's Task 2.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDocsTest extends AbstractPostgresContainerTest {

    @Autowired private MockMvc mockMvc;

    @Value("${springdoc.api-docs.path}")
    private String apiDocsPath;

    @Nested
    class GetOpenApiDocument {

        @Test
        void shouldReturnOk_whenOpenApiDocumentIsRequested() throws Exception {
            // arrange
            // act
            // assert
            mockMvc.perform(get(apiDocsPath)).andDo(print()).andExpect(status().isOk());
        }

        @Test
        void shouldReturnParseableOpenApiDocument_whenOpenApiDocumentIsRequested()
                throws Exception {
            // arrange
            // act
            var response = mockMvc.perform(get(apiDocsPath)).andDo(print()).andReturn();
            var body = response.getResponse().getContentAsString();
            var objectMapper = new ObjectMapper();
            var document = objectMapper.readTree(body);

            // assert
            Assertions.assertThat(document.path("openapi").asText()).startsWith("3.");
            Assertions.assertThat(document.path("paths").isObject()).isTrue();
            Assertions.assertThat(document.path("paths").isEmpty()).isFalse();
            Assertions.assertThat(document.path("paths").has(ApiPaths.BOARDS)).isTrue();
        }
    }
}

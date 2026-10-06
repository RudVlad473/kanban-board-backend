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
 * Regression guard for the OpenAPI endpoint at springdoc.api-docs.path.
 *
 * Why this is the way it is: io.confluent:kafka-avro-serializer transitively pulled in a
 * pre-jakarta swagger-annotations artifact that shadowed the jakarta one SpringDoc 2.8.8
 * needs, so every GET /api/docs returned 500 with NoSuchMethodError:
 * Parameter.validationGroups(). Extends AbstractPostgresContainerTest, not
 * AbstractAppTest, because it needs no fixtures (docs/CODE_STYLE.md rule 4). MockMvc
 * ignores server.servlet.context-path, so this class requests the bare path while a
 * deployment serves it under /api; that is a tier limit, not an oversight.
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

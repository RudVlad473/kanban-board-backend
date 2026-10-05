package com.vrudenko.kanban_board.config;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.vrudenko.kanban_board.constant.ErrorCode;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * Declare the {@code ProblemDetail} error envelope on every operation of the generated OpenAPI
 * document; springdoc's return-type reflection cannot see a {@code @ControllerAdvice}.
 *
 * <p>Describes two producers: {@link com.vrudenko.kanban_board.handler.GlobalExceptionHandler} and
 * {@link com.vrudenko.kanban_board.security.ProblemDetailAuthenticationEntryPoint}, the one
 * rejection path the handler structurally cannot reach (a genuinely unauthenticated request).
 *
 * <p>Decisions:
 *
 * <p>One global customizer bean, not per-endpoint annotations: those must be remembered on every
 * future controller method, the failure mode this bean exists to remove. {@link
 * #customise(OpenAPI)} walks every operation in the live document, so a new controller needs no
 * change.
 *
 * <p>The {@code code} enum is derived from {@link ErrorCode#values()} at document-build time, never
 * hand-listed, so spec and enum cannot drift. {@link ProblemDetailOpenApiCustomizerTest} guards
 * per-operation coverage and agreement with what the two producers actually emit.
 */
@Component
public class ProblemDetailOpenApiCustomizer implements GlobalOpenApiCustomizer {

    public static final String PROBLEM_DETAIL_SCHEMA_NAME = "ProblemDetail";
    public static final String PROBLEM_DETAIL_SCHEMA_REF =
            "#/components/schemas/" + PROBLEM_DETAIL_SCHEMA_NAME;

    // Insertion-ordered so a document diff stays stable across rebuilds. Each description names the
    // ErrorCode members a consumer sees on that status, per GlobalExceptionHandler's arms and
    // ProblemDetailAuthenticationEntryPoint.
    private static final Map<String, String> ERROR_RESPONSES = buildErrorResponses();

    private static Map<String, String> buildErrorResponses() {
        var responses = new LinkedHashMap<String, String>();
        responses.put(
                "400",
                "Malformed request body, failed field validation (VALIDATION_FAILED, with a"
                        + " per-field errors map), or a violated path/param constraint"
                        + " (CONSTRAINT_VIOLATION / ILLEGAL_ARGUMENT / MALFORMED_REQUEST_BODY).");
        responses.put(
                "401",
                "No session at all (UNAUTHENTICATED, produced by"
                        + " ProblemDetailAuthenticationEntryPoint) or rejected credentials on signin"
                        + " (BAD_CREDENTIALS, produced by GlobalExceptionHandler).");
        responses.put(
                "403",
                "An authenticated caller who does not own the requested resource"
                        + " (ACCESS_DENIED).");
        responses.put(
                "404",
                "The requested resource does not exist or is not visible to the caller"
                        + " (ENTITY_NOT_FOUND).");
        responses.put(
                "409",
                "Optimistic-lock version mismatch (OPTIMISTIC_LOCK_CONFLICT), a duplicate resource"
                        + " (DUPLICATE_RESOURCE), or a database integrity violation"
                        + " (DATA_INTEGRITY_VIOLATION).");
        responses.put("500", "An unhandled server-side failure (INTERNAL_ERROR).");
        return responses;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSchemas(PROBLEM_DETAIL_SCHEMA_NAME, problemDetailSchema());

        if (openApi.getPaths() == null) {
            return;
        }

        for (var pathItem : openApi.getPaths().values()) {
            for (var operation : pathItem.readOperations()) {
                if (operation.getResponses() == null) {
                    operation.setResponses(new ApiResponses());
                }
                var responses = operation.getResponses();
                ERROR_RESPONSES.forEach(
                        (statusCode, description) -> {
                            // Conditional insert keeps springdoc's own 200/201 intact and lets a
                            // future operation override one of these six without being stamped
                            // over.
                            if (!responses.containsKey(statusCode)) {
                                responses.addApiResponse(
                                        statusCode, problemDetailResponse(description));
                            }
                        });
            }
        }
    }

    /**
     * Return a fresh {@link ApiResponse} on every call.
     *
     * <p>Sharing six instances across ~24 operations would make the document a graph with shared
     * mutable nodes: a customizer that set a description on one operation's 404 would silently
     * mutate every 404. That costs ~140 short-lived allocations per document generation; the
     * serialized output is identical because the schema is a $ref either way.
     */
    private ApiResponse problemDetailResponse(String description) {
        var mediaType =
                new io.swagger.v3.oas.models.media.MediaType()
                        .schema(new Schema<>().$ref(PROBLEM_DETAIL_SCHEMA_REF));
        var content =
                new io.swagger.v3.oas.models.media.Content()
                        .addMediaType(MediaType.APPLICATION_PROBLEM_JSON_VALUE, mediaType);
        return new ApiResponse().description(description).content(content);
    }

    /**
     * Build the {@code ProblemDetail} component schema by hand, never by reflecting over the class.
     *
     * <p>{@code ProblemDetailJacksonMixin} flattens {@code ProblemDetail.getProperties()} onto the
     * root via {@code @JsonAnyGetter} (pinned by {@code GlobalExceptionHandlerTest}'s {@code
     * jsonPath("$.properties").doesNotExist()}), so a reflected schema would document a nested
     * {@code properties} object that never appears on the wire and omit {@code code}/{@code
     * errors}: a confidently wrong contract, worse than an absent one.
     */
    private Schema<?> problemDetailSchema() {
        var schema =
                new Schema<>()
                        .type("object")
                        .description(
                                "The RFC 7807 envelope every error response in this API uses.");

        schema.addProperties(
                "type",
                new StringSchema()
                        .format("uri")
                        .description("Always 'about:blank' unless a more specific type is set."));
        schema.addProperties(
                "title",
                new StringSchema()
                        .description(
                                "Absent unless explicitly set -- both producers build the envelope"
                                        + " through ProblemDetail.forStatusAndDetail, which"
                                        + " populates status and detail only."));
        schema.addProperties("status", new IntegerSchema().format("int32"));
        schema.addProperties("detail", new StringSchema());
        schema.addProperties(
                "instance",
                new StringSchema()
                        .format("uri")
                        .description(
                                "The authentication entry point sets this explicitly so both"
                                        + " producers' key sets agree."));

        var codeSchema = new StringSchema();
        codeSchema.setEnum(
                Arrays.stream(ErrorCode.values())
                        .map(ErrorCode::name)
                        .collect(Collectors.toList()));
        schema.addProperties(ErrorCode.CODE_PROPERTY, codeSchema);

        var errorsSchema =
                new MapSchema()
                        .additionalProperties(new StringSchema())
                        .description(
                                "Present only on the field-validation response: field name to"
                                        + " message.");
        schema.addProperties(ErrorCode.ERRORS_PROPERTY, errorsSchema);

        // Only status and the code property are required: detail can be null (the catch-all arm
        // passes ex.getMessage()), and title is never populated by either producer.
        schema.setRequired(List.of("status", ErrorCode.CODE_PROPERTY));

        return schema;
    }
}

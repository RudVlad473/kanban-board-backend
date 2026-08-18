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
 * Makes the generated OpenAPI document declare the {@code ProblemDetail} error envelope on every
 * operation (API-01). springdoc's reflection-based generation only documents a controller method's
 * declared return type -- it has no visibility into a {@code @ControllerAdvice} class elsewhere in
 * the application, so without this bean every {@code 400/401/403/404/409/500} this API actually
 * returns is absent from the document. The two producers this class describes are {@link
 * com.vrudenko.kanban_board.handler.GlobalExceptionHandler} (fourteen {@code @ExceptionHandler}
 * arms) and {@link com.vrudenko.kanban_board.security.ProblemDetailAuthenticationEntryPoint} (the
 * one rejection path the handler structurally cannot reach: a genuinely unauthenticated request).
 *
 * <p>This is deliberately one global customizer bean, not per-endpoint springdoc/swagger
 * annotation. D-08 rejects the per-endpoint mechanism by name, precisely because it has to be
 * remembered on every future controller method -- the failure mode API-01 exists to eliminate. A
 * new controller needs no change here: {@link #customise(OpenAPI)} walks every operation actually
 * present in the live document rather than a checked-in list.
 *
 * <p>The {@code code} property's enum (see {@link #problemDetailSchema()}) is derived from {@link
 * ErrorCode#values()} at document-build time, never hand-listed, so the spec and the enum cannot
 * drift apart. {@link ProblemDetailOpenApiCustomizerTest} is the regression guard proving both the
 * per-operation coverage and the agreement between this declared schema and what the two producers
 * above actually emit on real responses.
 */
@Component
public class ProblemDetailOpenApiCustomizer implements GlobalOpenApiCustomizer {

    public static final String PROBLEM_DETAIL_SCHEMA_NAME = "ProblemDetail";
    public static final String PROBLEM_DETAIL_SCHEMA_REF =
            "#/components/schemas/" + PROBLEM_DETAIL_SCHEMA_NAME;

    // Insertion-ordered so a document diff stays stable across rebuilds. Each description names the
    // concrete ErrorCode members a consumer will actually see on that status, taken from
    // GlobalExceptionHandler's handler arms and ProblemDetailAuthenticationEntryPoint.
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
                            // Conditional insert is load-bearing: it leaves springdoc's own
                            // generated 200/201 intact, and lets a future operation override one of
                            // these six with something more specific without this bean stamping
                            // over it.
                            if (!responses.containsKey(statusCode)) {
                                responses.addApiResponse(
                                        statusCode, problemDetailResponse(description));
                            }
                        });
            }
        }
    }

    /**
     * Returns a fresh {@link ApiResponse} instance on every call. The natural alternative -- build
     * six response objects once and attach the same six instances to all ~24 operations -- makes
     * the in-memory document a graph with shared mutable nodes: a future customizer that sets a
     * description on one operation's 404 would silently mutate every operation's 404. This trades
     * ~140 extra short-lived allocations, built once per document generation, for a document model
     * with no aliasing hazard. Serialized output is identical either way -- the schema is a $ref in
     * both cases, so nothing is duplicated on the wire.
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
     * Builds the {@code ProblemDetail} component schema by hand -- never by reflecting over the
     * {@code ProblemDetail} class. {@code ProblemDetailJacksonMixin} flattens {@code
     * ProblemDetail.getProperties()} onto the root via {@code @JsonAnyGetter} (pinned by {@code
     * GlobalExceptionHandlerTest}'s {@code jsonPath("$.properties").doesNotExist()}), so a
     * reflected schema would document a nested {@code properties} object that never appears on the
     * wire and would omit {@code code}/{@code errors} entirely -- publishing a confidently wrong
     * contract, worse than today's absent one.
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

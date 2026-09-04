package com.vrudenko.kanban_board.config;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.stereotype.Component;

/**
 * Publishes, on the generated OpenAPI document, the {@code pattern}/{@code minLength}/{@code
 * maxLength}/{@code format} constraints that arrive through this codebase's composed custom
 * validation annotations ({@code @ColumnColor}, {@code @BoardName}, {@code @DisplayName},
 * {@code @Password}, {@code @TaskTitle}, {@code @SubtaskTitle}, {@code @Description},
 * {@code @AppEmail}, {@code @OptionalNotBlank}) -- none of which swagger-core resolves on its own.
 *
 * <p><b>Observation 1 -- why this bean exists at all.</b> {@code
 * ModelResolver.applyBeanValidatorAnnotations} (swagger-core-jakarta 2.2.30, pulled in by
 * springdoc-openapi-starter-webmvc-ui 2.8.8 under Spring Boot 3.5.16) builds a {@code Map<String,
 * Annotation>} from a field's <em>directly declared</em> annotations only ({@code
 * ModelResolver.java:1696-1699}) and never opens a composed annotation's own meta-annotations. A
 * field annotated {@code @ColumnColor} therefore carries no {@code pattern} in the generated
 * document at all -- verified live on 2026-09-04: the production document
 * (https://kanban-board-rud-vlad-473.duckdns.org/api/docs) contains zero {@code pattern} keys and
 * zero {@code example} keys anywhere in {@code components.schemas}.
 *
 * <p><b>Observation 2 -- why this bean is registered as BOTH a {@link PropertyCustomizer} and a
 * {@link GlobalOpenApiCustomizer}, not the {@code PropertyCustomizer} alone.</b> For a field
 * carrying a <em>direct</em> {@code @NotBlank}/{@code @Size} alongside a composed annotation (e.g.
 * {@code SaveSubtaskRequestDTO.title}, direct {@code @NotBlank} plus composed
 * {@code @SubtaskTitle}), {@code ModelResolver.resolveProperties} calls {@code
 * applyBeanValidatorAnnotations} a <em>second</em> time, directly, after {@link #customize(Schema,
 * AnnotatedType)} has already run and returned ({@code ModelResolver.java:899-905} in the observed
 * sources: {@code ctxProperty} is the same object reference as the already-customized {@code
 * property} on the non-{@code allOf} resolution path this application uses). That second call
 * unconditionally re-runs {@code property.setMinLength(1)} for a direct {@code @NotBlank} with no
 * "already higher, leave it" guard -- confirmed empirically 2026-09-04 by instrumenting both this
 * bean and a live {@code bootRun} instance: {@link #customize(Schema, AnnotatedType)} correctly
 * computed and set {@code minLength=3} for {@code SaveSubtaskRequestDTO.title}, yet {@code GET
 * /api/docs} served {@code minLength=1} for that exact property. A {@code PropertyCustomizer} has
 * no extension point that runs after this second internal call; only a {@link
 * GlobalOpenApiCustomizer}, which springdoc runs as the last, whole-document post-processing phase,
 * is guaranteed to run after it. So {@link #customize(Schema, AnnotatedType)} both applies its
 * computed values immediately (correct for every field this second internal call does not touch)
 * AND records them into {@link #computedBySchema}, keyed by the schema name {@code
 * AnnotatedType.getParent().getName()} already assigns (no reflective class-name guessing, unlike a
 * {@code GlobalOpenApiCustomizer}-only design would need); {@link #customise(OpenAPI)} then
 * re-applies those same recorded values as the document's last word, overwriting whatever
 * swagger-core's own second pass did in between. What would make this false: a swagger-core release
 * that removes the second {@code applyBeanValidatorAnnotations} call, or guards it against lowering
 * an already-raised bound -- either way this bean's reassertion becomes a harmless no-op, since it
 * only ever restates values it already computed once.
 *
 * <p>Sibling to {@link ProblemDetailOpenApiCustomizer} -- that bean documents a shape swagger-core
 * cannot see at all because it lives outside any DTO ({@code @ControllerAdvice}); this one
 * documents a shape swagger-core CAN see but declines to fully read (and, per Observation 2,
 * partially reverts once it does).
 *
 * <p>{@link ComposedConstraintPropertyCustomizerTest} is the regression guard, proving both that
 * the values are actually published and that they agree with the real {@code Validator}.
 */
@Component
public class ComposedConstraintPropertyCustomizer
        implements PropertyCustomizer, GlobalOpenApiCustomizer {

    // schema name -> property name -> the values this bean computed for that property. Written
    // during the PropertyCustomizer phase (which alone has the field's annotations), read during
    // the GlobalOpenApiCustomizer phase (which springdoc guarantees runs last -- see Observation 2
    // above). A ConcurrentHashMap because document build can race on first request.
    private final Map<String, Map<String, Accumulator>> computedBySchema =
            new ConcurrentHashMap<>();

    @Override
    public Schema customize(Schema property, AnnotatedType type) {
        if (property == null || type.getCtxAnnotations() == null || !isStringSchema(property)) {
            return property;
        }

        var accumulator = seedFrom(property);
        for (var annotation : type.getCtxAnnotations()) {
            if (annotation.annotationType().isAnnotationPresent(Constraint.class)) {
                walk(annotation, new LinkedHashSet<>(), accumulator);
            }
        }

        accumulator.applyTo(property);
        record(type, accumulator);
        return property;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        openApi.getComponents()
                .getSchemas()
                .forEach(
                        (schemaName, schema) -> {
                            var recorded = computedBySchema.get(schemaName);
                            if (recorded == null || schema.getProperties() == null) {
                                return;
                            }
                            recorded.forEach(
                                    (propertyName, accumulator) -> {
                                        var propertySchema =
                                                (Schema<?>)
                                                        schema.getProperties().get(propertyName);
                                        if (propertySchema != null) {
                                            accumulator.applyTo(propertySchema);
                                        }
                                    });
                        });
    }

    private void record(AnnotatedType type, Accumulator accumulator) {
        if (type.getParent() == null
                || type.getParent().getName() == null
                || type.getPropertyName() == null) {
            return;
        }
        computedBySchema
                .computeIfAbsent(type.getParent().getName(), key -> new ConcurrentHashMap<>())
                .put(type.getPropertyName(), accumulator);
    }

    // openapi: 3.1.0 serializes a one-element `types` set as the singular "type" key, but the
    // in-memory model can carry getType() == null with getTypes() == {"string"} -- mirrors
    // org/springdoc/core/utils/SchemaUtils.java:222-225, the in-dependency precedent for covering
    // both spellings.
    private boolean isStringSchema(Schema<?> property) {
        var type = property.getType();
        if (type == null && property.getTypes() != null && property.getTypes().size() == 1) {
            type = property.getTypes().iterator().next();
        }
        return "string".equals(type);
    }

    private Accumulator seedFrom(Schema<?> property) {
        var accumulator = new Accumulator();
        if (property.getPattern() != null) {
            accumulator.patterns.add(property.getPattern());
        }
        accumulator.minLength = property.getMinLength();
        accumulator.maxLength = property.getMaxLength();
        return accumulator;
    }

    // The @Constraint gate is what keeps the walk out of java.lang.annotation.* (@Documented is
    // itself @Documented, so an ungated walk does not terminate). `pathVisited` is scoped to the
    // CURRENT recursion path, not the whole call: it is added on the way down and removed on the
    // way back up. That distinction is load-bearing -- the same annotation TYPE legitimately
    // appears twice with DIFFERENT attribute values on a field carrying two composed annotations
    // that each bring their own @Pattern (e.g. UpdateBoardRequestDTO.name: @BoardName's own
    // @Pattern and @OptionalNotBlank's own @Pattern are both jakarta.validation.constraints.Pattern
    // but carry different regexp() values). A set that is never cleared would treat the class alone
    // as "already handled" and silently drop the second occurrence's contribution.
    private void walk(
            Annotation annotation,
            Set<Class<? extends Annotation>> pathVisited,
            Accumulator accumulator) {
        var annotationType = annotation.annotationType();
        if (!pathVisited.add(annotationType)) {
            return;
        }
        contribute(annotation, accumulator);
        collectSchemaMeta(annotationType, accumulator);
        for (var meta : annotationType.getAnnotations()) {
            if (meta.annotationType().isAnnotationPresent(Constraint.class)) {
                walk(meta, pathVisited, accumulator);
            }
        }
        pathVisited.remove(annotationType);
    }

    private void contribute(Annotation annotation, Accumulator accumulator) {
        if (annotation instanceof Pattern pattern) {
            accumulator.patterns.add(pattern.regexp());
        } else if (annotation instanceof Size size) {
            if (size.min() != 0) {
                raiseMinLength(accumulator, size.min());
            }
            if (size.max() != Integer.MAX_VALUE) {
                lowerMaxLength(accumulator, size.max());
            }
        } else if (annotation instanceof NotBlank || annotation instanceof NotEmpty) {
            raiseMinLength(accumulator, 1);
        } else if (annotation instanceof Email) {
            if (accumulator.format == null) {
                accumulator.format = "email";
            }
        }
        // Every other Jakarta constraint contributes nothing to a string schema's
        // pattern/length/format and is silently ignored. @Email.regexp() is deliberately never
        // read: no site in this codebase moves it away from its ".*" default.
    }

    // Reads only example()/description() off a meta io.swagger.v3.oas.annotations.media.Schema --
    // that annotation carries ~40 other attributes, and ignoring all of them is deliberate scope,
    // not an oversight. First occurrence wins; a later composed annotation on the same field never
    // overwrites an example/description an earlier one already contributed.
    private void collectSchemaMeta(
            Class<? extends Annotation> annotationType, Accumulator accumulator) {
        var schemaMeta =
                annotationType.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class);
        if (schemaMeta == null) {
            return;
        }
        if (accumulator.example == null && !schemaMeta.example().isEmpty()) {
            accumulator.example = schemaMeta.example();
        }
        if (accumulator.description == null && !schemaMeta.description().isEmpty()) {
            accumulator.description = schemaMeta.description();
        }
    }

    private void raiseMinLength(Accumulator accumulator, int candidate) {
        accumulator.minLength =
                accumulator.minLength == null
                        ? candidate
                        : Math.max(accumulator.minLength, candidate);
    }

    private void lowerMaxLength(Accumulator accumulator, int candidate) {
        accumulator.maxLength =
                accumulator.maxLength == null
                        ? candidate
                        : Math.min(accumulator.maxLength, candidate);
    }

    // Never loosen: minLength/maxLength/patterns are seeded from the schema swagger already built,
    // so applyTo can only tighten what a direct annotation already published -- true both the first
    // time customize() calls it and the second time customise(OpenAPI) does.
    private static final class Accumulator {
        private final Set<String> patterns = new LinkedHashSet<>();
        private Integer minLength;
        private Integer maxLength;
        private String format;
        private String example;
        private String description;

        void applyTo(Schema<?> property) {
            property.setMinLength(minLength);
            property.setMaxLength(maxLength);
            if (format != null && property.getFormat() == null) {
                property.setFormat(format);
            }
            if (example != null && property.getExample() == null) {
                property.setExample(example);
            }
            if (description != null && property.getDescription() == null) {
                property.setDescription(description);
            }
            if (patterns.isEmpty()) {
                return;
            }
            if (patterns.size() == 1) {
                property.setPattern(patterns.iterator().next());
                return;
            }
            // size >= 2: every regex but the LAST becomes a zero-width lookahead; the last one
            // consumes. Each "^(?:Ri)$" is the faithful translation of Java's whole-string
            // Matcher.matches() into ECMA-262.
            //
            // The trailing consuming term is load-bearing for CONSUMERS, not for correctness under
            // the spec. JSON Schema evaluates `pattern` as an unanchored search, under which an
            // all-lookahead conjunction is already correct. But a generated client that instead
            // full-matches it (Java Pattern.matches, Python re.fullmatch, or a generator that
            // wraps the pattern in ^...$) sees a zero-width expression, which matches ONLY the
            // empty string -- so every valid value would be rejected client-side before a request
            // was ever sent. Measured 2026-09-04 on UpdateBoardRequestDTO.name's own two regexes:
            // all-lookahead form gave search=true/fullmatch=FALSE for "Platform Launch", this form
            // gives true/true, and both forms still reject "   " and "" under either reading.
            var ordered = List.copyOf(patterns);
            var lastIndex = ordered.size() - 1;
            var conjunction =
                    ordered.subList(0, lastIndex).stream()
                            .map(regex -> "(?=^(?:" + regex + ")$)")
                            .collect(Collectors.joining());
            property.setPattern(conjunction + "^(?:" + ordered.get(lastIndex) + ")$");
        }
    }
}

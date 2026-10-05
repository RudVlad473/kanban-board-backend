package com.vrudenko.kanban_board.config;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.vrudenko.kanban_board.dto.annotation.BmpOnly;

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
 * Publish the {@code pattern}/{@code minLength}/{@code maxLength}/{@code format} constraints that
 * composed validation annotations carry onto the generated OpenAPI document.
 *
 * <p>Decisions:
 *
 * <p><b>Observation 1 -- why this bean exists.</b> {@code
 * ModelResolver.applyBeanValidatorAnnotations} (swagger-core-jakarta 2.2.30, pulled in by
 * springdoc-openapi-starter-webmvc-ui 2.8.8 under Spring Boot 3.5.16) builds its annotation map
 * from a field's <em>directly declared</em> annotations only ({@code ModelResolver.java:1696-1699})
 * and never opens a composed annotation's meta-annotations. Verified live on 2026-09-04: the
 * production document (https://kanban-board-rud-vlad-473.duckdns.org/api/docs) contained zero
 * {@code pattern} and zero {@code example} keys in {@code components.schemas}.
 *
 * <p><b>Observation 2 -- why this bean is BOTH a {@link PropertyCustomizer} and a {@link
 * GlobalOpenApiCustomizer}.</b> For a field with a <em>direct</em> {@code @NotBlank}/{@code @Size}
 * next to a composed annotation (e.g. {@code SaveSubtaskRequestDTO.title}), {@code
 * ModelResolver.resolveProperties} calls {@code applyBeanValidatorAnnotations} a <em>second</em>
 * time after {@link #customize(Schema, AnnotatedType)} has returned ({@code
 * ModelResolver.java:899-905}; {@code ctxProperty} is the same object as the customized {@code
 * property} on the non-{@code allOf} path this application uses). That call unconditionally re-runs
 * {@code property.setMinLength(1)} with no "already higher" guard. Confirmed 2026-09-04 by
 * instrumenting this bean in a live {@code bootRun}: {@code customize} set {@code minLength=3} for
 * {@code SaveSubtaskRequestDTO.title}, yet {@code GET /api/docs} served {@code minLength=1}. Only a
 * {@link GlobalOpenApiCustomizer}, springdoc's last whole-document phase, runs after that second
 * call, so {@code customize} applies its values immediately AND records them in {@link
 * #computedBySchema} (keyed by {@code AnnotatedType.getParent().getName()}); {@link
 * #customise(OpenAPI)} re-applies them last, tightening only (see {@link
 * Accumulator#reassertOn(Schema)}). False if a swagger-core release removes the second call or
 * guards it against lowering a raised bound: the reassertion then becomes a harmless no-op.
 *
 * <p>{@link ComposedConstraintPropertyCustomizerTest} proves the values are published and agree
 * with the real {@code Validator}.
 */
@Component
public class ComposedConstraintPropertyCustomizer
        implements PropertyCustomizer, GlobalOpenApiCustomizer {

    // schema name -> property name -> values computed in the PropertyCustomizer phase, re-applied
    // in the GlobalOpenApiCustomizer phase (Observation 2). Concurrent: the document build can race
    // on first request.
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
                                            accumulator.reassertOn(propertySchema);
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

    // Cover both spellings: openapi 3.1.0 serializes a one-element `types` set as "type", but the
    // in-memory model can carry getType() == null with getTypes() == {"string"}.
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
        accumulator.minLengthUnits = property.getMinLength();
        accumulator.maxLength = property.getMaxLength();
        return accumulator;
    }

    // Walk composed constraint annotations along the current recursion path only.
    //
    // The @Constraint gate keeps the walk out of java.lang.annotation.* (@Documented is itself
    // @Documented, so an ungated walk never terminates). pathVisited is removed on the way back up
    // because one annotation type can appear twice with different attributes
    // (UpdateBoardRequestDTO.name: @BoardName's and @OptionalNotBlank's @Pattern carry different
    // regexp() values); a never-cleared set would drop the second contribution.
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
        if (annotation.annotationType().isAnnotationPresent(BmpOnly.class)) {
            accumulator.bmpConfined = true;
        }
        if (annotation instanceof Pattern pattern) {
            // Publish nothing when no ECMA-262 equivalent is provable (see ecmaEquivalentOf).
            ecmaEquivalentOf(pattern.regexp(), pattern.flags())
                    .ifPresent(accumulator.patterns::add);
        } else if (annotation instanceof Size size) {
            // Publish a length bound in code points, not UTF-16 code units.
            //
            // Decisions:
            // @Size counts UTF-16 code units; JSON Schema minLength/maxLength count code points,
            // and a code point is one or two units, so the bounds diverge in opposite directions.
            // minLength: publishing @Size verbatim REJECTS values the server ACCEPTS (two emoji
            //   are 4 units, so @Size(min = 3) takes them, but only 2 code points, so a
            //   spec-compliant generated client refuses a legal request). Publish ceil(n / 2),
            //   the largest bound no accepted value can fail (proof: codePointSafeMinLength and
            //   the equivalence test). A constraint that enumerates its permitted characters
            //   carries @BmpOnly, and then the exact bound is published.
            // maxLength: published verbatim on purpose. It ACCEPTS values the server rejects,
            //   costing a 400 the validator was always going to produce; halving it would shrink
            //   every ASCII-only field's documented ceiling for a divergence already in the
            //   tolerated direction. False the day a generated client treats a published
            //   maxLength as a hard contract (property-based fuzzing up to the documented
            //   boundary, say).
            // Corrected 2026-09-05: publishing minLength verbatim was once called "provably safe"
            // after checking only the tolerated direction.
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
        // Other Jakarta constraints contribute nothing to a string schema and are ignored.
        // @Email.regexp() is deliberately never read: no site moves it off its ".*" default.
    }

    /**
     * Translate a Java {@code Pattern} into the ECMA-262 dialect JSON Schema {@code pattern} uses,
     * or return empty when equivalence cannot be PROVEN.
     *
     * <p>Rewrites an unescaped, not-inside-a-class {@code .} to {@code [\s\S]} under {@code
     * DOTALL}, and {@code \s}/{@code \S} to explicit ASCII classes (Java's are ASCII-only, ECMA's
     * match U+00A0).
     *
     * <p>Decisions:
     *
     * <p>Publishing nothing beats publishing a WRONG pattern. Before this method, {@link
     * #contribute(Annotation, Accumulator)} republished {@code regexp()} verbatim and dropped
     * {@code flags()}, so {@link com.vrudenko.kanban_board.dto.annotation.OptionalNotBlank}'s
     * {@code DOTALL} was lost and its {@code \S} was read as ECMA's Unicode-aware version. Proven
     * live (node v24.19.0, 2026-09-05) to make the published pattern REJECT values the real {@code
     * jakarta.validation.Validator} ACCEPTS: a multi-line title ({@code "a\nb"}) and a value made
     * solely of {@code U+00A0} -- the document-stricter-than-enforcer direction this bean exists to
     * avoid.
     *
     * <p>Any OTHER {@code flags()} value ({@code CASE_INSENSITIVE} chief among them), an unescaped
     * capturing group, or an unrecognised {@code (?...} construct returns empty: inline flags like
     * {@code (?i)} are a hard {@code SyntaxError} in ECMA-262, and a capturing group would shift
     * every later group's number once concatenated into the conjunction {@link
     * Accumulator#applyPattern(Schema)} builds (latent today: no regex here uses one).
     */
    // Package-private: the test derives expected patterns from an annotation's own
    // regexp()/flags(), never a hand-copied literal.
    static Optional<String> ecmaEquivalentOf(String javaRegexp, Pattern.Flag[] flags) {
        for (var flag : flags) {
            if (flag != Pattern.Flag.DOTALL) {
                return Optional.empty();
            }
        }
        if (hasUnsupportedGroupConstruct(javaRegexp)) {
            return Optional.empty();
        }
        // Every flag present (if any) is DOTALL, as the loop above guaranteed.
        var dotAll = flags.length > 0;
        return translateDotAndWhitespaceShorthand(javaRegexp, dotAll);
    }

    // Reject an unescaped '(' outside a character class unless it opens '(?:', '(?=', '(?!', '(?<='
    // or '(?<!'. A bare capturing group, inline flag group "(?i)" or named group "(?<name>" is
    // rejected rather than guessed at.
    private static boolean hasUnsupportedGroupConstruct(String regexp) {
        var escaped = false;
        var inCharClass = false;
        for (var i = 0; i < regexp.length(); i++) {
            var c = regexp.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                inCharClass = true;
                continue;
            }
            if (c == ']') {
                inCharClass = false;
                continue;
            }
            if (c == '(' && !inCharClass) {
                if (i + 1 >= regexp.length() || regexp.charAt(i + 1) != '?') {
                    return true;
                }
                var rest = regexp.substring(i + 2);
                var recognised =
                        rest.startsWith(":")
                                || rest.startsWith("=")
                                || rest.startsWith("!")
                                || rest.startsWith("<=")
                                || rest.startsWith("<!");
                if (!recognised) {
                    return true;
                }
            }
        }
        return false;
    }

    // Single pass tracking escape and character-class state, so '.', '\s' and '\S' are rewritten
    // only where they carry their regex meaning.
    private static Optional<String> translateDotAndWhitespaceShorthand(
            String javaRegexp, boolean dotAll) {
        var out = new StringBuilder();
        var escaped = false;
        var inCharClass = false;
        for (var i = 0; i < javaRegexp.length(); i++) {
            var c = javaRegexp.charAt(i);
            if (escaped) {
                escaped = false;
                if (c == 's') {
                    out.append(inCharClass ? " \\t\\n\\x0B\\f\\r" : "[ \\t\\n\\x0B\\f\\r]");
                } else if (c == 'S') {
                    if (inCharClass) {
                        // \S inside an open character class has no simple negated substitute, and
                        // guessing wrong is worse than publishing nothing.
                        return Optional.empty();
                    }
                    out.append("[^ \\t\\n\\x0B\\f\\r]");
                } else {
                    out.append('\\').append(c);
                }
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '[') {
                inCharClass = true;
                out.append(c);
                continue;
            }
            if (c == ']') {
                inCharClass = false;
                out.append(c);
                continue;
            }
            if (c == '.' && !inCharClass && dotAll) {
                out.append("[\\s\\S]");
                continue;
            }
            out.append(c);
        }
        if (escaped) {
            // Trailing lone backslash: malformed input, bail rather than guess.
            return Optional.empty();
        }
        return Optional.of(out.toString());
    }

    // Read only example()/description() off a meta Schema annotation: its ~40 other attributes are
    // deliberately out of scope. First occurrence wins; a later composed annotation never
    // overwrites an example/description an earlier one contributed.
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

    /**
     * Converts a {@code @Size(min)} expressed in UTF-16 code units into the largest JSON Schema
     * {@code minLength}, counted in code points, that no server-accepted value can fail.
     *
     * <p>{@code units(v) >= n} implies {@code codePoints(v) >= units(v) / 2 >= n / 2}, so {@code
     * ceil(n / 2)} is safe and any larger bound is not: for odd {@code n} an all-astral value of
     * exactly {@code n + 1} units has {@code (n + 1) / 2} code points, which is {@code ceil(n / 2)}
     * exactly.
     */
    private static int codePointSafeMinLength(int unitsMin) {
        return (unitsMin + 1) / 2;
    }

    private void raiseMinLength(Accumulator accumulator, int candidateUnits) {
        accumulator.minLengthUnits =
                accumulator.minLengthUnits == null
                        ? candidateUnits
                        : Math.max(accumulator.minLengthUnits, candidateUnits);
    }

    private void lowerMaxLength(Accumulator accumulator, int candidate) {
        accumulator.maxLength =
                accumulator.maxLength == null
                        ? candidate
                        : Math.min(accumulator.maxLength, candidate);
    }

    private static final class Accumulator {
        private final Set<String> patterns = new LinkedHashSet<>();

        // In UTF-16 code units, the unit @Size and @NotBlank are declared in. Converted to code
        // points by codePointSafeMinLength at publish time, never before -- raising the bound has
        // to happen in one unit, and the published document speaks the other.
        private Integer minLengthUnits;

        // Set when any constraint on this property declares @BmpOnly. A BMP-confined value has one
        // code unit per code point, so the unit bound needs no conversion to be safe.
        private boolean bmpConfined;
        private Integer maxLength;
        private String format;
        private String example;
        private String description;

        /**
         * Apply every computed value unconditionally; phase 1 ({@link PropertyCustomizer}) only.
         *
         * <p>Safe only because {@link ComposedConstraintPropertyCustomizer#seedFrom(Schema)} filled
         * this accumulator from this exact {@code property} before folding in any composed value,
         * so nothing on it can be loosened. That fails the second time the schema is touched
         * (Observation 2 on the enclosing class); {@link #reassertOn(Schema)} is the tighten-only
         * counterpart.
         */
        void applyTo(Schema<?> property) {
            property.setMinLength(publishedMinLength());
            property.setMaxLength(maxLength);
            applyMeta(property);
            applyPattern(property);
        }

        /**
         * Re-apply the recorded values in phase 2 ({@link GlobalOpenApiCustomizer}, the document's
         * last word), tightening only.
         *
         * <p>Raises {@code minLength} past what the schema carries, lowers {@code maxLength} only
         * below it, sets {@code pattern} only when it has none, and lowers a {@code minLength} it
         * can identify as swagger-core's own unconverted code-unit bound (see {@link
         * #isUnconvertedUnitBound(Integer)}).
         *
         * <p>Decisions:
         *
         * <p>The second phase must not call {@link #applyTo(Schema)}: replaying a phase-1 snapshot
         * could OVERWRITE a value something else set on the same schema object in between with an
         * older, looser one. Confirmed 2026-09-04 by triple-boot: a field-level
         * {@code @Schema(minLength = 10, maxLength = 20, pattern = "^Sprint .*$")} on {@code
         * SaveBoardRequestDTO.name} was published intact with this bean disabled and REPLACED by
         * the looser {@code 1 / 64 / ^[a-zA-Z0-9 ]*$} with it enabled; neutering only phase 2
         * restored the field-level values. Latent today (no DTO field carries its own
         * {@code @Schema} constraint), but {@code @Schema} is now in this codebase's vocabulary
         * ({@link com.vrudenko.kanban_board.dto.annotation.Password}, {@link
         * com.vrudenko.kanban_board.dto.annotation.BoardName}), and phase 2 only ever restates its
         * own recorded values, not whatever else is on the schema by then.
         */
        void reassertOn(Schema<?> property) {
            var published = publishedMinLength();
            if (published != null) {
                var current = property.getMinLength();
                if (current == null || published > current || isUnconvertedUnitBound(current)) {
                    property.setMinLength(published);
                }
            }
            if (maxLength != null
                    && (property.getMaxLength() == null || maxLength < property.getMaxLength())) {
                property.setMaxLength(maxLength);
            }
            applyMeta(property);
            if (property.getPattern() == null) {
                applyPattern(property);
            }
        }

        private Integer publishedMinLength() {
            if (minLengthUnits == null) {
                return null;
            }
            return bmpConfined ? minLengthUnits : codePointSafeMinLength(minLengthUnits);
        }

        /**
         * Report whether {@code current} is swagger-core's unconverted UTF-16 unit bound rather
         * than a deliberate choice by someone else, so it may be lowered to the converted value.
         *
         * <p>Decisions:
         *
         * <p>The second phase otherwise only tightens, because a value on the schema by then may
         * have been set by something with more authority than this bean (a field-level
         * {@code @Schema}), and replaying a phase-1 snapshot over it would loosen it.
         * swagger-core's own second pass is the exception: it re-derives {@code minLength} from
         * {@code @Size} in code units, which is wrong for the document rather than merely stricter.
         * It is recognisable because it equals the unit bound this accumulator already holds.
         */
        private boolean isUnconvertedUnitBound(Integer current) {
            return minLengthUnits != null && minLengthUnits.equals(current);
        }

        private void applyMeta(Schema<?> property) {
            if (format != null && property.getFormat() == null) {
                property.setFormat(format);
            }
            if (example != null && property.getExample() == null) {
                property.setExample(example);
            }
            if (description != null && property.getDescription() == null) {
                property.setDescription(description);
            }
        }

        private void applyPattern(Schema<?> property) {
            if (patterns.isEmpty()) {
                return;
            }
            if (patterns.size() == 1) {
                property.setPattern(patterns.iterator().next());
                return;
            }
            // Every regex but the LAST becomes a zero-width lookahead; the last one consumes. Each
            // "^(?:Ri)$" translates Java's whole-string Matcher.matches() into ECMA-262.
            //
            // Decisions:
            // The trailing consuming term exists for CONSUMERS, not spec correctness: JSON Schema
            // evaluates `pattern` as an unanchored search, where an all-lookahead conjunction is
            // already correct. A generated client that full-matches it (Pattern.matches,
            // re.fullmatch, or a generator wrapping it in ^...$) sees a zero-width expression that
            // matches ONLY the empty string and rejects every valid value before a request is
            // sent. Measured 2026-09-04 on UpdateBoardRequestDTO.name's two regexes: the
            // all-lookahead form gave search=true/fullmatch=FALSE for "Platform Launch", this
            // form gives true/true, and both forms still reject "   " and "" under either reading.
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

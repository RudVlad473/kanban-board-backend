package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declare that a composed constraint admits no character outside the Basic Multilingual Plane, so a
 * length in UTF-16 code units equals one in code points for any value it accepts.
 *
 * Apply it only to a constraint whose @Pattern enumerates its permitted characters (a
 * closed character class such as ^[a-zA-Z ]*$). A pattern that merely requires some
 * structure, like @OptionalNotBlank's .*\S.* or @Password's four
 * character-class lookaheads, still admits astral characters elsewhere in the value and must NOT
 * carry this marker.
 *
 * Decisions:
 *
 * ComposedConstraintPropertyCustomizer reads it: it otherwise publishes ceil(n /
 * 2) for a @Size(min = n), because a value outside the BMP costs two code units per code
 * point and a bound published verbatim would reject values the server accepts. Where this marker is
 * present that divergence is unreachable, and the exact bound is published instead.
 *
 * This is a declaration, not a derivation, deliberately. Proving it would mean statically
 * analysing the annotation's regex for astral ranges, and hand-rolled regex analysis is where this
 * area's defects have lived. ComposedConstraintPropertyCustomizerTest.BmpOnlyDeclarations
 * guards every use by driving the annotation's own @Pattern against an astral value and
 * requiring a rejection: a guard against the declaration going stale, never a proof it was right.
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BmpOnly {}

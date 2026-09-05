package com.vrudenko.kanban_board.dto.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declare that a composed constraint annotation admits no character outside the Basic Multilingual
 * Plane, so a length counted in UTF-16 code units and one counted in Unicode code points are always
 * equal for any value it accepts.
 *
 * <p>Read by {@code ComposedConstraintPropertyCustomizer}, which otherwise has to publish {@code
 * ceil(n / 2)} for a {@code @Size(min = n)} — a value outside the BMP costs two code units per code
 * point, so a bound published verbatim would reject values the server accepts. Where this marker is
 * present that divergence is unreachable, and the exact bound is published instead.
 *
 * <p><b>This is a declaration, not a derivation, and that is deliberate.</b> Proving it would mean
 * statically analysing the annotation's regex for astral ranges, and hand-rolled regex analysis is
 * where this area's defects have actually lived. {@code
 * ComposedConstraintPropertyCustomizerTest.BmpOnlyDeclarations} guards every use by driving the
 * annotation's own {@code @Pattern} against an astral value and requiring a rejection — a guard
 * against the declaration going stale, never a proof that it was right.
 *
 * <p>Apply it only to a constraint whose {@code @Pattern} enumerates its permitted characters (a
 * closed character class such as {@code ^[a-zA-Z ]*$}). A pattern that merely requires some
 * structure — {@code @OptionalNotBlank}'s {@code .*\S.*}, or {@code @Password}'s four
 * character-class lookaheads — still admits astral characters everywhere else in the value and must
 * NOT carry this marker.
 */
@Documented
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface BmpOnly {}

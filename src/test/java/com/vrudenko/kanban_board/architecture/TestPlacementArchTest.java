package com.vrudenko.kanban_board.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Turns docs/CODE_STYLE.md rule 13 (a new test class belongs in a named subpackage, never the root
 * package) from a convention that had drifted silently -- 11 stray files, 2,000+ lines, found only
 * by a manual audit in quick task 260812-eg8 -- into a build-failing check.
 *
 * <p>Unlike {@link LayeringArchTest}, this class carries no {@code importOptions} restriction on
 * its {@code @AnalyzeClasses} declaration: it must import the test source set itself (a test class
 * misplaced in the root package is exactly what it is checking for), so it cannot be folded into
 * {@link LayeringArchTest}, which declares {@code ImportOption.DoNotIncludeTests} specifically to
 * keep the test source set out of its own imported graph.
 *
 * <p><strong>This is a floor, not a ceiling.</strong> It constrains where a test class's file lives
 * -- directly in the root package versus any named subpackage -- not whether it lives in the
 * <em>correct</em> subpackage for what it actually tests. A column-concern test filed under {@code
 * e2e/task/} instead of {@code e2e/column/} passes this rule; getting that judgement right is what
 * docs/CODE_STYLE.md rule 4's purpose test, and code review, are for.
 */
@AnalyzeClasses(packages = "com.vrudenko.kanban_board")
public class TestPlacementArchTest {

    /**
     * A class whose simple name marks it as a test by this codebase's own naming convention
     * (docs/CODE_STYLE.md: {@code {ClassUnderTest}Test.java}) -- ending in {@code Test} or the
     * plural {@code Tests} (Spring Initializr's own convention for the generated {@code
     * KanbanBoardApplicationTests}, the one named exemption below).
     */
    private static final DescribedPredicate<JavaClass> HAS_TEST_MARKER_SIMPLE_NAME =
            DescribedPredicate.describe(
                    "has a simple name ending in \"Test\" or \"Tests\"",
                    javaClass ->
                            javaClass.getSimpleName().endsWith("Test")
                                    || javaClass.getSimpleName().endsWith("Tests"));

    @ArchTest
    static final ArchRule test_classes_must_not_reside_directly_in_the_root_package =
            noClasses()
                    .that(HAS_TEST_MARKER_SIMPLE_NAME)
                    .and()
                    .doNotHaveSimpleName("KanbanBoardApplicationTests")
                    .should()
                    .resideInAPackage("com.vrudenko.kanban_board")
                    .because(
                            "a new test class must be filed under one of this tree's existing"
                                    + " subpackages (service/, controller/, e2e/<entity>/,"
                                    + " activitylog/, config/, security/, handler/, architecture/,"
                                    + " support/...), per docs/CODE_STYLE.md rule 13 -- never"
                                    + " directly in the root package, which is where 11 files"
                                    + " drifted to unnoticed before this rule existed (quick task"
                                    + " 260812-eg8). KanbanBoardApplicationTests is the sole named"
                                    + " exemption: Spring Initializr's own conventional"
                                    + " root-package context-load smoke test, kept beside"
                                    + " KanbanBoardApplication by idiomatic convention rather than"
                                    + " technical necessity.");
}

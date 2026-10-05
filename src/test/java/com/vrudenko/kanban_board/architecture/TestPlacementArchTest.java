package com.vrudenko.kanban_board.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces docs/CODE_STYLE.md rule 13: a test class lives in a named subpackage, never the root
 * package.
 *
 * <p>Unlike {@link LayeringArchTest}, this class must import the test source set itself, since a
 * misplaced test is exactly what it looks for, so it cannot share that class's {@code
 * ImportOption.DoNotIncludeTests}.
 *
 * <p>Known holes: a floor, not a ceiling. It checks only that a test sits in some named subpackage,
 * not the correct one: a column test under {@code e2e/task/} passes. Rule 4's purpose test and code
 * review cover that.
 */
@AnalyzeClasses(packages = "com.vrudenko.kanban_board")
public class TestPlacementArchTest {

    /**
     * A simple name ending in {@code Test}, or {@code Tests} for the Initializr-generated {@code
     * KanbanBoardApplicationTests}, the one named exemption below.
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

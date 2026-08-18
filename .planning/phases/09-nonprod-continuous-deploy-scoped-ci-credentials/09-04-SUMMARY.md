---
phase: 09-nonprod-continuous-deploy-scoped-ci-credentials
plan: 04
subsystem: api-contract
tags: [openapi, springdoc, error-envelope, problem-detail, api-01]
dependency-graph:
  requires: []
  provides:
    - "ProblemDetailOpenApiCustomizer (springdoc GlobalOpenApiCustomizer bean)"
    - "ProblemDetail component schema in the generated OpenAPI document"
    - "ProblemDetailOpenApiCustomizerTest regression guard"
  affects:
    - "docs/ARCHITECTURE.md (Layering and access control section)"
tech-stack:
  added: []
  patterns:
    - "Global springdoc customizer (org.springdoc.core.customizers.GlobalOpenApiCustomizer) rather than per-endpoint swagger annotation"
    - "Hand-built OpenAPI component schema derived from a Java enum's values() at document-build time, never reflected over the DTO class"
key-files:
  created:
    - src/main/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizer.java
    - src/test/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizerTest.java
    - .planning/todos/pending/2026-08-18-500-problemdetail-detail-carries-raw-exception-message.md
  modified:
    - docs/ARCHITECTURE.md
decisions:
  - "Uniform six-code attachment (approach B) over per-operation introspection (approach C): the introspection heuristic would reintroduce exactly the per-operation reasoning D-08 removes, and the over-documentation cost (a couple of unreachable status codes on a few operations) is one-directional and bounded, unlike the under-documentation gap this plan closes"
  - "ProblemDetail schema is hand-built, never reflected over the ProblemDetail class, because ProblemDetailJacksonMixin flattens the extension map onto the root via @JsonAnyGetter -- a reflected schema would publish a nested `properties` object that never appears on the wire"
  - "A fresh ApiResponse instance is constructed per (operation, status) pair rather than sharing six instances across ~24 operations, trading ~140 short-lived allocations for a document model with no shared-mutable-node aliasing hazard"
metrics:
  duration: "~35 minutes (first to last task commit)"
  completed: 2026-08-18
status: complete
actuals:
  tokens: 7611
  tasks: 3
  commits: 3
---

# Phase 9 Plan 04: Publish the ProblemDetail Error Envelope in the OpenAPI Document Summary

Closed API-01 by adding one global springdoc customizer bean that attaches all six error status
codes (400/401/403/404/409/500) -- each referencing a single hand-built `ProblemDetail` component
schema -- to every operation in the generated OpenAPI document, backed by an automated guard test
that both sweeps every operation for coverage and cross-checks the declared schema against real
sampled responses from both independent envelope producers.

## What Was Built

**Task 1 -- `ProblemDetailOpenApiCustomizer` + coverage guard (TDD, tracer).** Wrote the failing
guard test first (`ProblemDetailOpenApiCustomizerTest.ErrorResponseCoverage.shouldDeclareEveryStandardErrorResponse_whenEveryOperationIsInspected`)
against the live document fetched exactly as `OpenApiDocsTest` does (`@Value` into
`springdoc.api-docs.path`, MockMvc GET, parse with `ObjectMapper` -- never autowiring the `OpenAPI`
bean, since springdoc caches the built document and a test holding the live instance could mutate
shared state). **RED confirmed:** the sweep failed with 138 `missing status` violations spanning
all 23 operations that existed before the bean (later measured at 24 operations once the reset
controller's absence under the `test` profile was accounted for -- see Task 2). Then wrote
`ProblemDetailOpenApiCustomizer`, a `@Component` implementing `org.springdoc.core.customizers.GlobalOpenApiCustomizer`
(confirmed present in springdoc 2.8.8's `swagger-openapi-starter-common` jar before use). It:

- Registers a hand-built `ProblemDetail` component schema (never reflected over the `ProblemDetail`
  class -- see Decisions) with seven properties (`type`, `title`, `status`, `detail`, `instance`,
  the `code` property whose enum is derived from `ErrorCode.values()`, and the `errors` map
  property), with `status` and `code` marked required.
- Walks every `PathItem.readOperations()` in the live document (no hardcoded verb list) and, for
  each of the six status codes, inserts a fresh `ApiResponse` **only if the operation does not
  already declare that status** -- the conditional insert that leaves springdoc's own generated
  `200`/`201` responses untouched.

**GREEN confirmed:** the guard test passed after the bean landed; `GlobalExceptionHandlerTest`,
`ErrorEnvelopeConsistencyTest`, and `OpenApiDocsTest` all still passed unmodified;
`./gradlew spotlessCheck` passed.

**Task 2 -- Schema fidelity + anti-vacuous floor + proof the guard guards.** Extended the test
class with:

- `shouldDocumentAtLeastTwentyOperations_whenSpecIsGenerated` -- floor of 20; **measured 24**
  operations at write time (the `nonprod`-profiled reset controller is absent under the `test`
  profile, as expected).
- `shouldPreserveGeneratedSuccessResponse_whenCustomizerHasRun` -- proves the boards-listing `GET`
  still declares its `200`.
- `ProblemDetailSchemaFidelity` nested class: `shouldDeclareCodeEnumMatchingErrorCodeValues_whenSchemaIsGenerated`
  (set-equality against `ErrorCode.values()`) plus three `shouldDeclareEveryKeyEmitted_when*`
  methods sampling real responses -- a `404` from `GlobalExceptionHandler` (missing board), a `400`
  from `GlobalExceptionHandler` carrying the field-validation `errors` map (over-long board name),
  and a `401` from `ProblemDetailAuthenticationEntryPoint` (no session cookie at all). **All three
  matched the declared schema in both directions with no correction needed** -- observed emitted key
  sets: `404` -> `{instance, code, detail, type, title, status}`; `400` -> `{instance, code, detail,
  type, title, errors, status}`; `401` -> `{instance, code, detail, type, title, status}`, all
  subsets of the schema's declared properties.
- **Deliberately broke both halves of the guard once, per the plan's Task 2C, then restored:**
  removing status `500` from the customizer's response map reddened the coverage sweep with
  "missing status 500" reported against all 24 operations in one run; removing the `status`
  property from the schema reddened all three fidelity methods, each reporting `["status"]` as an
  emitted-but-undeclared key. Both restored and re-verified green (confirmed the customizer file is
  byte-identical to its Task 1 committed state after the experiment).
- **Full-suite run:** `./gradlew test` green in **~6m46s** with the new customizer bean now present
  in every `@SpringBootTest` context repo-wide. No independent pre-plan baseline wall-clock was
  captured in this session to diff against; the ~6m46s figure is the full-suite result measured
  after this plan's changes landed.

**Task 3 -- Docs + deferred finding.** Added a bullet to `docs/ARCHITECTURE.md`'s "Layering and
access control" section, immediately after the existing 401/403 bullet, naming
`ProblemDetailOpenApiCustomizer` and `ProblemDetailOpenApiCustomizerTest` by name (satisfying the
file's own "one grep from confirmation" rule), the mechanism, why it's central rather than
per-endpoint (D-08), and the discovery story (D-07: found by a downstream frontend consumer, not by
this repo's own tests, which assert runtime bodies and never the generated document). Filed
`.planning/todos/pending/2026-08-18-500-problemdetail-detail-carries-raw-exception-message.md`
recording that `handleGeneralException`'s `500` `detail` carries the raw `ex.getMessage()` --
pre-existing behaviour, not fixed here (D-08's additive-only scope), now easier to notice because
the `500` response is documented on a `permitAll` path (threat `T-09-21`).

## Deviations from Plan

None -- plan executed exactly as written, including the tracer feedback gate (Task 1's guard
verified green before Task 2's expansion) and the mandatory induced-failure proof in Task 2C.

## Auth Gates

None encountered.

## Known Stubs

None. The `ErrorCode.ERRORS_PROPERTY` ("errors") schema property is declared but only exercised on
the field-validation sample (`400`); this matches the plan's design ("present only on the
field-validation response") and is not a stub.

## Threat Flags

None. This plan's own `<threat_model>` (T-09-20 through T-09-25, T-09-SC) already anticipates and
dispositions every surface this change introduces -- publishing the error envelope, the anti-drift
guard, the anti-regression guard against the conditional-insert being weakened, and the
document-generation cost. No new surface outside that register was found during implementation.

## Self-Check: PASSED

- `src/main/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizer.java` -- FOUND
- `src/test/java/com/vrudenko/kanban_board/config/ProblemDetailOpenApiCustomizerTest.java` -- FOUND
- `.planning/todos/pending/2026-08-18-500-problemdetail-detail-carries-raw-exception-message.md` -- FOUND
- `docs/ARCHITECTURE.md` (modified) -- FOUND
- Commit `70c34b5` (Task 1) -- FOUND in `git log`
- Commit `d36c65a` (Task 2) -- FOUND in `git log`
- Commit `f15f429` (Task 3) -- FOUND in `git log`

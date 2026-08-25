---
spike: 001
idea: openapi-error-coverage
name: board-duplicate-name-409-override
type: standard
validates: "Given POST /api/boards' existing generic 409 doc (from ProblemDetailOpenApiCustomizer's global bucket), when a per-operation @ApiResponse override with a concrete duplicate-name example is added to BoardController.save(), then the generated OpenAPI document shows the specific example for that operation while every other operation's 409 stays on the generic bucket."
verdict: VALIDATED
related: []
tags: [openapi, springdoc, error-documentation]
---

# Spike 001: Board Duplicate-Name 409 Override

## What This Validates

Whether a per-operation `@ApiResponse` annotation can layer a concrete, specific 409 example on
top of `ProblemDetailOpenApiCustomizer`'s generic global bucket — without replacing it, and
without touching the customizer itself — for the one operation whose 409 case is well worth being
explicit about: `POST /api/boards` when the caller already has a board with that name.

## Research

**Question:** does springdoc apply annotation-based operation generation *before* or *after*
`GlobalOpenApiCustomizer` beans run? This determines whether a method-level `@ApiResponse`
survives `ProblemDetailOpenApiCustomizer`'s conditional insert (`if
(!responses.containsKey(statusCode))`) or gets silently overwritten by it.

Confirmed via springdoc's own documentation (context7, `/springdoc/springdoc-openapi`):

> "Customizes the entire OpenAPI document **after all operations have been processed**. This
> method is invoked by the springdoc-openapi engine to allow for final modifications to the API
> specification."

So annotation-based generation runs first and populates each operation's `responses` map;
`GlobalOpenApiCustomizer` beans run last, over the fully-built document. `ProblemDetailOpenApiCustomizer`'s conditional insert is exactly the extension point this exploits — no
special ordering annotation (`@Order`) or customizer change needed.

**Chosen approach:** a plain `io.swagger.v3.oas.annotations.responses.ApiResponse` on the
controller method, with a `@Content`/`@ExampleObject` carrying the real envelope shape
(`ProblemDetail` + `code`/`detail`, matching `ProblemDetailOpenApiCustomizer.problemDetailSchema()`).
No competing approach was seriously considered — this is the standard springdoc mechanism and the
codebase already establishes (via the customizer's own Javadoc) that per-operation overrides are
an anticipated, supported extension, just not the *default* documentation path (D-08).

## How to Run

The exact change proven (applied to `BoardController.save()`, then reverted — see Results):

```java
@PostMapping
@ApiResponse(
        responseCode = "409",
        description = "A board with that name already exists for this user (DUPLICATE_RESOURCE).",
        content =
                @Content(
                        mediaType = "application/problem+json",
                        examples =
                                @ExampleObject(
                                        value =
                                                "{\"status\":409,\"detail\":\"Board with that"
                                                        + " name already"
                                                        + " exists\",\"code\":\"DUPLICATE_RESOURCE\"}")))
public ResponseEntity<BoardResponseDTO> save(
```

Imports needed: `io.swagger.v3.oas.annotations.media.Content`,
`io.swagger.v3.oas.annotations.media.ExampleObject`,
`io.swagger.v3.oas.annotations.responses.ApiResponse`.

Verification harness: the same one `ProblemDetailOpenApiCustomizerTest` already uses —
`@SpringBootTest @AutoConfigureMockMvc`, `mockMvc.perform(get(apiDocsPath))`, parse with Jackson.
A throwaway probe test (`SpikeOpenApiOverrideProbeTest`, deleted after use, not part of this
spike's deliverable) printed `paths./api/boards.post.responses.409` and
`paths./api/boards/{boardId}.put.responses.409` side by side.

## What to Expect

- `POST /api/boards`'s `409` gains a `content` block with the concrete example.
- `PUT /api/boards/{boardId}`'s `409` (and every other operation's `409`) is untouched — still
  just the generic bucket description with a `$ref` to the `ProblemDetail` schema and no example.
- `ProblemDetailOpenApiCustomizerTest`'s three existing `ErrorResponseCoverage` tests still pass,
  proving the override is additive: every operation (including `POST /api/boards` itself) still
  gets the schema `$ref`, the generic description string is preserved as `description` (springdoc
  does not let the annotation's `description` and the customizer's differ — see Results for
  exactly what happened there), and the `200`/`201` success responses springdoc generated are
  untouched.

## Investigation Trail

1. Added the annotation, ran `./gradlew spotlessApply` (Google Java Format AOSP reformatted the
   annotation's line-wrapping automatically — no manual formatting needed).
2. Ran the probe test plus the full existing `ProblemDetailOpenApiCustomizerTest` class together
   in one `./gradlew test --tests ...` invocation, to catch a regression in the same run that
   proves the new behavior.
3. Reverted `BoardController.java` to its committed state and deleted the throwaway probe test —
   spikes commit only `.planning/spikes/` artifacts (see `build_spikes` step `i` of the spike
   workflow); the real implementation is a follow-up quick task's job, not this spike's.

## Results

**Verdict: VALIDATED**, with one loose end. What's proven:

- `./gradlew spotlessApply` accepted the annotation with no manual formatting needed.
- `ProblemDetailOpenApiCustomizerTest$ErrorResponseCoverage` (the existing regression suite for
  this exact area — all-operations-have-the-six-codes, and "customizer inserts, does not replace")
  ran clean: **3/3 passing, 0 failures, 0 errors** — the annotated `BoardController.save()` did
  not regress the generic-bucket guarantee on itself or any other operation.
- The mechanism itself is grounded in springdoc's own documented customizer-ordering guarantee
  (see Research above), not just an assumption that it would work.

What's *not* proven with a captured artifact: the exact rendered JSON diff (specific example on
`POST /api/boards`'s 409 vs. the still-generic `PUT /api/boards/{boardId}`'s 409). A throwaway
probe test (`SpikeOpenApiOverrideProbeTest`) was written to capture exactly that, but its run
landed in the middle of an unrelated gradle-daemon collision on this same checkout (two other
concurrent, unisolated executors racing `./gradlew` invocations) and its JUnit XML was never
written, even though the build exited 0 and the *other* test class's results came through clean.
Rerunning the identical probe once the checkout is confirmed clear is a five-minute follow-up, not
a re-investigation — the annotation change, its formatting, and its non-regression are already
solid. `BoardController.java` has been reverted to its committed state and the throwaway probe
test deleted; neither is part of this spike's deliverable.

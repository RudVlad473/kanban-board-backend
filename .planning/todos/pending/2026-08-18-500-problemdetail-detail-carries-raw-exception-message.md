---
created: 2026-08-18T19:14:34.000Z
title: 500 ProblemDetail response's `detail` carries the raw exception message
area: security
severity: medium
resolves_phase: null
files:
  - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
---

## Problem

`GlobalExceptionHandler.handleGeneralException` (the `Exception.class` catch-all arm) copies
`ex.getMessage()` straight into the `detail` field of the `500` `ProblemDetail` envelope:

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ProblemDetail> handleGeneralException(Exception ex) {
    var problem =
            ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    problem.setProperty(ErrorCode.CODE_PROPERTY, ErrorCode.INTERNAL_ERROR.name());

    return ResponseEntity.status(problem.getStatus()).body(problem);
}
```

This means raw internal exception text reaches any client that can trigger an unhandled failure --
potentially including details about internal implementation (class names, SQL fragments, file
paths, etc.) that should not be exposed across a trust boundary.

Surfaced during plan 09-04 (API-01: publish the `ProblemDetail` error envelope in the generated
OpenAPI document), threat `T-09-21` in that plan's threat model. The OpenAPI document is served on
a `permitAll` path (`springdoc.api-docs.path`), so documenting the `500` response as part of API-01
makes this pre-existing behaviour easier to notice from outside -- publishing the spec does not
create the leak, but it lowers the effort needed to discover it.

This is pre-existing runtime behaviour, not introduced by plan 09-04. Fixing it there would have
been a runtime API behaviour change, which that plan's governing decision (D-08, "additive
spec-generation config with no runtime/API behaviour change") explicitly places out of scope --
09-04 documents the response shape and files this finding rather than fixing it inline.

## Solution

Replace the raw `ex.getMessage()` passed into `ProblemDetail.forStatusAndDetail` on the `500` arm
with a fixed, generic string (e.g. "An unexpected error occurred"), while still logging the real
exception (message + stack trace) server-side for diagnosis. Verify: (1) a triggered unhandled
exception's HTTP response body no longer contains the original exception message text, (2) the
real exception is still visible in application logs for the same request, (3) `GlobalExceptionHandlerTest`
and `ErrorEnvelopeConsistencyTest` still pass, updated if they assert on the previous message shape.

**Trigger:** not gating any current phase. Cite `GlobalExceptionHandler.handleGeneralException` and
threat `T-09-21` (plan 09-04) when picked up.

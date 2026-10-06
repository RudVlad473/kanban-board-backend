---
created: 2026-10-06T00:00:00.000Z
title: "OpenAPI declares ProblemDetail.instance as format: uri, but the app returns a path"
area: api-documentation
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
  - src/main/java/com/vrudenko/kanban_board/security/ProblemDetailAuthenticationEntryPoint.java
---

## Problem

Found by scripts/fuzz/run-fuzz.py baseline (2026-10-06, one run, 25 examples per operation, fixed
seed): 24 "Response violates schema" failures, 22 of them for this one reason (the other two are the
nullable column colour, filed separately).

The published error schema declares `instance` with `format: uri`, and the app answers with a
path such as `/api/boards/00`:

    curl -X DELETE http://127.0.0.1:<port>/api/boards/00   (signed in)
    404 {"type":"about:blank","title":"Not Found","status":404,"detail":"Board was not found","instance":"/api/boards/00","code":"ENTITY_NOT_FOUND"}
    Schemathesis: "/api/boards/00" is not a "uri"

RFC 9457 allows `instance` to be a URI reference, which includes a relative path, so the declared
`format` is stricter than the standard. Spike 003 saw the same class (24 on the 401 and 404
responses) before any fix. The 22 here are 12 400 responses and 10 404 responses across every operation family; no 401 was
sampled this run, so the 401 producer is listed from the spike, not from this baseline.

## Solution

Pick one: drop `format: uri` from the `instance` schema (the producer is within the RFC), or emit
absolute URIs from both producers. The first is a documentation-only change. Until it lands, 22
failures of this class bury everything else in the fuzz report.

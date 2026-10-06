---
created: 2026-10-06T00:00:00.000Z
title: "A multipart/form-data Content-Type with no boundary returns 500 INTERNAL_ERROR"
area: api
severity: moderate
files:

  - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
---

## Problem

Found by scripts/fuzz/run-fuzz.py baseline (2026-10-06, one run, 25 examples per operation, fixed
seed): 2 "Server error on unexpected Content-Type" failures, on GET /boards and
GET /users/me/theme. Reproduction, signed in:

    curl -X GET -H 'Content-Type: multipart/form-data' http://127.0.0.1:<port>/api/boards
    500 {"type":"about:blank","title":"Internal Server Error","status":500,"detail":"Failed to parse multipart servlet request","instance":"/api/boards","code":"INTERNAL_ERROR"}

The malformed header is the client's fault, and a 4xx is the correct answer, but the request falls
into the catch-all arm of the exception handler and is reported as a server fault. This is the
only 500 the baseline reproduced. It is separate from the existing todo about missing
Content-Type validation on write endpoints (2026-08-20): this one is reachable on a GET with no
body, so declaring `consumes` on the write handlers would not fix it.

## Solution

Map the multipart parse failure to 400 (or 415) in `GlobalExceptionHandler`, through the same
RFC 7807 envelope as every other client error, and add a test sending the malformed header to a
GET endpoint. Rerun the fuzz baseline and confirm the class is gone.

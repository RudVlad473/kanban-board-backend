---
created: 2026-10-06T00:00:00.000Z
title: "OpenAPI documents 200 for create operations that return 201"
area: api-documentation
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java
  - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
  - src/main/java/com/vrudenko/kanban_board/security/AuthenticationController.java
---

## Problem

Found by scripts/fuzz/run-fuzz.py baseline (2026-10-06, one run, 25 examples per operation, fixed
seed): 3 "Undocumented HTTP status code" failures.

`POST /boards` documents 200, 400, 401, 403, 404, 409 and 500 but returns 201:

    curl -X POST -H 'Content-Type: application/json' -d '{"name": "Platform Launch"}' http://127.0.0.1:<port>/api/boards
    201 {"id":"...","name":"Platform Launch","version":0,"createdAt":"..."}

The same failure showed on the stateful chain for the column-create operation. `POST /signup` also
returns 201 and spike 003 saw it undocumented; this baseline sample did not reproduce that one, so
it is listed here as probable, not confirmed.

## Solution

Annotate each create handler with the real 201 response so the generated document lists it, or
make the handlers return the documented 200. The first matches the status the clients already
receive.

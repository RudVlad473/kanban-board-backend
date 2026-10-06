---
created: 2026-10-06T00:00:00.000Z
title: "OpenAPI request schemas omit the Password, AppEmail and name/title constraints, so schema-valid bodies get 400"
area: api-documentation
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SignupRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/user_dto/SigninRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/dto/annotation/Password.java
---

## Problem

Found by scripts/fuzz/run-fuzz.py baseline (2026-10-06, one run, 25 examples per operation, fixed
seed): 9 "API rejected schema-compliant request" failures, on POST /signin, POST /signup, the
column, task and subtask create operations and the stateful chain.

The generator sends a body the published schema allows and the server answers 400
VALIDATION_FAILED, because the custom composed annotations (`@Password`, `@AppEmail`, and the
column name, task title and subtask title constraints) do not reach the generated schema:

    curl -X POST -H 'Content-Type: application/json' -d '{"displayName": "Ada Lovelace", "email": "0@0.com", "password": "aA0:0"}' http://127.0.0.1:<port>/api/signup
    400 {"code":"VALIDATION_FAILED","errors":{"password":"Password must contain at least ..."}}

    curl -X POST -H 'Content-Type: application/json' -d '{"title": "00"}' http://127.0.0.1:<port>/api/boards/0/columns/0
    400 {"code":"VALIDATION_FAILED","errors":{"title":"Task title cannot be l..."}}

Clients and fuzzers reading the document cannot know the length and pattern rules, so a share of
every fuzz run is spent on bodies the server was always going to refuse.

## Solution

Teach the generated document the constraints the composed annotations carry (a springdoc
customizer, or explicit schema annotations on the DTO fields), then rerun the fuzz baseline and
confirm the count drops.

---
created: 2026-08-11T00:00:00.000Z
resolved: 2026-08-13
title: Delete dead DeleteBoardByIdRequestDTO class
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/dto/board_dto/DeleteBoardByIdRequestDTO.java
---

## Problem

`DeleteBoardByIdRequestDTO` (`src/main/java/com/vrudenko/kanban_board/dto/board_dto/DeleteBoardByIdRequestDTO.java`)
has zero references anywhere else in the codebase. `BoardController.deleteById` takes a bare
`@PathVariable @NotBlank String boardId`, not a request-body DTO — board deletion never had a
request body to bind. Confirmed by the 260811-qru DTO/controller audit:

```
grep -rn "DeleteBoardByIdRequestDTO" src/main src/test
```

matches only the class's own declaration (`DeleteBoardByIdRequestDTO.java:14`), nothing else. No
controller method, mapper, or test references it.

## Solution

Delete `src/main/java/com/vrudenko/kanban_board/dto/board_dto/DeleteBoardByIdRequestDTO.java`. Run
`./gradlew spotlessCheck test` after removal to confirm nothing depended on it silently (a compile
failure would surface a wrong "zero references" claim). No behavior change expected — this class
was never wired into any request path.

## Resolution

Resolved by quick task 260813-euo, exactly as proposed. Re-verified the "zero references" claim by
a fresh grep across `src/main` and `src/test` immediately before deleting (this repo's own
precedent from quick tasks 260803-m3i/260803-ns9) — only the class's own declaration matched.
Deleted the file; the project compiled and the full suite passed, confirming nothing depended on
it silently.

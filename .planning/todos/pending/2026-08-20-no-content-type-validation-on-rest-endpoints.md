---
created: 2026-08-20T00:00:00.000Z
title: "No explicit Content-Type validation on REST endpoints"
area: backend
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java
  - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java
  - src/main/java/com/vrudenko/kanban_board/controller/TaskController.java
  - src/main/java/com/vrudenko/kanban_board/controller/SubtaskController.java
  - src/main/java/com/vrudenko/kanban_board/handler/GlobalExceptionHandler.java
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V13.1.5, V13.2.5).

A grep across all controller classes for `consumes=`/`produces=` returns zero matches.
`GlobalExceptionHandler`'s `Exception.class` catch-all has no arm for
`HttpMediaTypeNotSupportedException` or `HttpMediaTypeNotAcceptableException`, so a request with an
unexpected content type never gets the 406/415 ASVS expects.

## Solution

Add explicit `consumes = MediaType.APPLICATION_JSON_VALUE` to
`@PostMapping`/`@PutMapping`/`@PatchMapping` handlers across all controllers. Add dedicated
`@ExceptionHandler` arms for both exceptions in `GlobalExceptionHandler`, routed through the same
RFC 7807 `ProblemDetail` envelope as every other exception. Add a test sending an unexpected
`Content-Type` and asserting 415.

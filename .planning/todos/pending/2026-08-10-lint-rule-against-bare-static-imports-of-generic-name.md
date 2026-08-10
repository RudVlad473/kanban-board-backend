---
created: 2026-08-10T10:59:00.000Z
title: Consider a lint rule against bare static imports of generically-named methods (e.g. MockMvcResultHandlers.print)
area: testing
severity: minor
files:
  - src/test/java/com/vrudenko/kanban_board/controller/BoardControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/TaskControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/SubtaskControllerTest.java
  - src/test/java/com/vrudenko/kanban_board/controller/ColumnControllerTest.java
---

## Problem

Four `*ControllerTest` classes statically import `org.springframework.test.web.servlet.result.MockMvcResultHandlers.print`
and call it bare — `.andDo(print())` — 38 call sites total across the four files. `print` is a
generic enough name (unlike, say, `status` or `jsonPath` from the neighboring `MockMvcResultMatchers`
static imports already used throughout the test suite) that a bare call site reads ambiguously: a
reader has to check the import list to know it's Spring MockMvc's debug-print handler and not some
other `print`/logging utility.

This is a real but narrow readability nit, not a defect — flagged during a conversation aside while
Wave 6 (plan 07.1-08) was executing, not discovered as part of any plan's own scope.

## Solution

Options to evaluate, not yet decided:

1. **Qualify the call site instead of static-importing:** `import org.springframework.test.web.servlet.result.MockMvcResultHandlers;` (class import, not static) and call `MockMvcResultHandlers.print()` explicitly at each of the 38 sites. Zero tooling cost, purely a find-and-replace, but manual convention with nothing enforcing it going forward.
2. **A Checkstyle/Error Prone rule flagging static imports of specific generic method names** (`print`, `log`, similar) so this can't silently regrow. Spotless/Google Java Format doesn't police import *style* choices (static vs. qualified) on its own — this would need a dedicated check, e.g. Checkstyle's `AvoidStaticImport` with an `excludes` allowlist for the legitimately-fine static imports already used everywhere else (`status`, `jsonPath`, `post`, `put`, etc. from `MockMvcRequestBuilders`/`MockMvcResultMatchers`).
3. **Do nothing** — the existing static-import style (`post`, `status`, `jsonPath`, etc.) is already this codebase's pervasive convention per every test class read this session; `print` may be the one generic-enough outlier not worth a dedicated rule for.

Whoever picks this up should decide whether a blanket "no static imports of generic single-word
method names" rule is worth the tooling setup, versus just fixing the 38 call sites by hand and
leaving it as an unenforced convention.

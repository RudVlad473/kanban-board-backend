---
created: 2026-08-12T00:00:00.000Z
title: Four remaining ResponseEntity.created(URI.create(request.getRequestURI())) sites now diverge from signup's Location pattern
area: backend
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java:54
  - src/main/java/com/vrudenko/kanban_board/controller/BoardController.java:80
  - src/main/java/com/vrudenko/kanban_board/controller/ColumnController.java:51
  - src/main/java/com/vrudenko/kanban_board/controller/TaskController.java:65

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Quick task 260812-hs4 changed `AuthenticationController.signup`'s `Location` header from
`URI.create(request.getRequestURI())` to a header built from the injected
`server.servlet.context-path` plus `ApiPaths.USERS`/`ApiPaths.ME` (D-02/D-04). That was a
deliberate, scoped divergence from one repo-wide idiom, not a project-wide decision: the four
sites named above (`BoardController:54,80`, `ColumnController:51`, `TaskController:65`) still use
`URI.create(request.getRequestURI())` and were explicitly out of that task's scope.

This is a genuine, open consistency question, not a defect in either form: the four remaining
sites' `Location` at least names the parent collection a resource was `POST`ed to (e.g.
`/boards/{boardId}/columns` after creating a column under it), which is meaningfully different
from signup's old header (which named the `/signup` route itself -- a URI describing no resource
at all). Whether the four domain-controller sites should be normalized to the same
configuration-derived pattern signup now uses, left as `request.getRequestURI()`, or judged
case-by-case, was not decided by 260812-hs4 -- see that plan's design rationale, non-obvious
trade-off 6.

## Solution

Not scoped here. A future pass should decide, and document in `docs/CODE_STYLE.md`, whether
`Location` headers across this codebase should uniformly resolve to a real, dereferenceable
resource URI (closer to REST's HATEOAS spirit) or whether "names the parent collection POSTed to"
remains an accepted alternative for the four domain-controller sites. Whichever direction is
chosen, apply it consistently rather than leaving the split open indefinitely.

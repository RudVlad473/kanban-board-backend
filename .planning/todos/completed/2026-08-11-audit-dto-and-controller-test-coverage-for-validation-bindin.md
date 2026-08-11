---
created: 2026-08-11T16:50:00.000Z
title: Audit DTO and controller test coverage for validation/binding blind spots
area: testing
severity: major
files:
  - src/main/java/com/vrudenko/kanban_board/dto/
  - src/main/java/com/vrudenko/kanban_board/controller/
  - src/test/java/com/vrudenko/kanban_board/controller/
---

## Problem

Two real, silently-shipped defects surfaced this session from the same root cause: existing
controller-tier tests exercised the service layer directly (or worked around a binding bug via
query params) instead of driving the real HTTP JSON-body path, so a class of bugs specific to the
controller/DTO boundary went uncaught.

Concrete evidence, both found 2026-08-11 while closing quick task `260811-me4`:

1. `TaskController.addSubtaskByTaskId`'s `dto` parameter was missing `@RequestBody`, so Spring MVC
   bound it as a model attribute from query/form params instead of the JSON body — silently
   dropping every field a real client would send in the request body. The existing controller test
   for this endpoint (`TaskControllerTest.AddSubtaskByTaskId`) had been written to send
   `.param("title", title)` instead of a JSON body, which passed against the buggy binding and
   therefore never caught it. See `.planning/todos/completed/2026-08-09-fix-subtask-creation-dto-missing-requestbody-binds-as-mode.md`.
2. While fixing (1), found `SaveSubtaskRequestDTO.title` has no `@NotBlank`, unlike every sibling
   creation DTO (`SaveBoardRequestDTO`, `SaveTaskRequestDTO`, `SaveColumnRequestDTO` all carry
   `@NotBlank` alongside their `@Size`/custom annotation). A `{}` or null-title JSON body reaches
   the database's `NOT NULL` constraint instead of returning a clean 400. See
   `.planning/todos/pending/2026-08-11-save-subtask-request-dto-missing-notblank-on-title.md`.

Both gaps are the kind that a systematic audit — rather than accidental discovery mid-fix — would
catch in one pass. Given two real hits from one small area (Subtask creation alone), it's worth
checking whether the same two failure modes (missing `@RequestBody` on a create/update endpoint;
missing/inconsistent validation annotations on a DTO field relative to its siblings) recur
elsewhere across the other six controllers and their DTOs.

## Solution

Not yet investigated — a systematic pass, not a single fix. Candidates for whoever picks this up:

1. **Binding audit**: grep every `@PostMapping`/`@PutMapping`/`@PatchMapping` handler across all
   controllers (`BoardController`, `ColumnController`, `TaskController`, `SubtaskController`,
   `TaskMoveController`, `UserController`, `ActivityController`) for DTO parameters, confirm each
   carries `@RequestBody` and `@Valid`, and cross-check that its corresponding controller-tier test
   actually POSTs/PUTs a real JSON body (not a query param, not a direct service call) — the same
   blind spot that hid the subtask bug for an unknown length of time.
2. **Validation-annotation audit**: for every `Save*RequestDTO`/`Update*RequestDTO`, list its
   fields and their validation annotations side-by-side with sibling DTOs of the same shape
   (create vs. create, update vs. update) to spot asymmetries like the missing `@NotBlank` found
   above — a field present with validation on one DTO but silently unvalidated on an
   analogous field of another.
3. **Coverage classification**: this project's `docs/CODE_STYLE.md` rule 4 already documents a
   which-base-class decision rule (service = business-logic/edge-case coverage, controller =
   single-endpoint HTTP contract). Check whether every controller create/update endpoint actually
   has a controller-tier (not just service-tier) test — a gap in that split is exactly what let
   this bug ship unnoticed.
4. Related, already-filed, narrower todos not to duplicate: the reconcile
   VALIDATION_FAILED-vs-CONSTRAINT_VIOLATION todo
   (`.planning/todos/pending/2026-08-10-reconcile-validation-failed-vs-constraint-violation-envelope.md`)
   is about response-envelope consistency for validation failures that DO fire, a different
   question from whether validation annotations exist and are exercised in the first place.

Whoever picks this up should produce a findings list (which endpoints/DTOs have gaps) before
deciding whether to fix everything in one pass or file individual follow-up todos per finding —
this todo is the audit, not a blank check to fix everything found under its own scope.

---
created: 2026-09-04T18:00:00.000Z
title: Allow editing a column's color after creation
area: api
severity: minor
files:
  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/UpdateColumnRequestDTO.java
  - src/main/java/com/vrudenko/kanban_board/mapper/ColumnMapper.java
  - src/main/java/com/vrudenko/kanban_board/service/ColumnService.java
---

## Problem

Quick task 260904-obv (D-5) added an optional `color` field to `SaveColumnRequestDTO`, accepted
only at column-creation time. `UpdateColumnRequestDTO` was deliberately left untouched, so a column
whose color can be set once and never changed is a half-feature: a client that mistypes a color, or
simply wants to recolor an existing column, has no recourse but to delete and recreate the column
(losing its tasks in the process, since delete cascades).

## Why this was deferred rather than folded into 260904-obv

`UpdateColumnRequestDTO` already carries a documented exception to this codebase's `Update*RequestDTO`
convention (D-02, quick task 260811-ufu): `name` is mandatory there, unlike every other single-field
update DTO, because a version-only column update has no use case. Adding `color` as a second,
genuinely optional field reopens that DTO's fixed-shape contract (`docs/CODE_STYLE.md` rule 6) — it
would need an `atLeastOneFieldPopulated()` cross-field check now that there are two independently
optional-ish fields, and a real design decision about whether `name` should become optional
alongside it. That is a design question in its own right, too large to bolt onto a creation-path
task.

## Suggested approach

- Add `@ColumnColor private String color;` to `UpdateColumnRequestDTO`, following the pattern already
  established for `SaveColumnRequestDTO.color`.
- Decide whether `name` should become optional now that a second field exists, or whether
  `atLeastOneFieldPopulated()` should require `name` (mandatory) OR `color` (optional) — this needs a
  real product decision, not just a mechanical copy of rule 6's shape.
- Extend `ColumnControllerTest.UpdateById` with an update-color-only case and a version-conflict case
  matching the existing `name` coverage.

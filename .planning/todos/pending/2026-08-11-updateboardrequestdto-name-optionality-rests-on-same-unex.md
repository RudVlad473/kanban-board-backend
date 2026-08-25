---
created: 2026-08-11T00:00:00.000Z
title: UpdateBoardRequestDTO.name's optionality rests on the same unexamined assumption D-02 declined to accept for the column DTO
area: backend
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/dto/board_dto/UpdateBoardRequestDTO.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Quick task 260811-ufu (D-02) decided `UpdateColumnRequestDTO.name` stays mandatory (`@NotBlank`,
rejects `null`) rather than becoming optional, because investigation found no test in
`BoardServiceTest`/`BoardControllerTest` and no mockup evidence of a "touch the resource without
changing its one substantive field" flow for a single-field DTO.

`UpdateBoardRequestDTO.name` is the same shape — `name` is the DTO's only independently optional
field besides the mandatory `version` — and rests on exactly the same unexamined assumption: no
test currently exercises a genuine version-only board update (a PUT that omits `name` entirely,
asserting the board's name is unchanged and only `version` incremented), and no mockup evidence
was found of that flow either. 260811-ufu deliberately did not change this behavior — closing the
whitespace-blank gap on `UpdateBoardRequestDTO.name` (via `@OptionalNotBlank`) was in scope; the
board DTO's own null-optionality was explicitly out of scope and carried forward here instead.

## Solution

Decide, with the same rigor D-02 applied to the column DTO: either

1. Find or write a test proving a genuine version-only board update use case exists (a UI flow, a
   documented client behavior), which would justify keeping `name` optional as-is; or

2. Conclude no such use case exists and make `UpdateBoardRequestDTO.name` mandatory (`@NotBlank`,
   dropping `@OptionalNotBlank`), matching `UpdateColumnRequestDTO`'s now-documented shape, with a
   comparable class-level Javadoc explaining the choice.

Either outcome should update `UpdateBoardRequestDTO`'s D-13 comment (already corrected by
260811-ufu to stop claiming parity with `UpdateColumnRequestDTO`) with the final resolution.

---
created: 2026-10-06T00:00:00.000Z
title: "ColumnResponseDTO.color is documented as a non-null string, but a column can come back with color null"
area: api-documentation
severity: minor
files:

  - src/main/java/com/vrudenko/kanban_board/dto/column_dto/ColumnResponseDTO.java
---

## Problem

Found by scripts/fuzz/run-fuzz.py baseline (2026-10-06, one run, 25 examples per operation, fixed
seed): 2 "Response violates schema" failures, on GET /boards/{boardId}/columns and on
PUT /boards/{boardId}/columns/{columnId}, both from a column created without a colour:

    200 [{"id":"...","name":"...","version":0,"position":0,"color":null}]
    Schemathesis: null is not of type "string" (schema /components/schemas/ColumnResponseDTO/properties/color)

The response schema gives `color` as `type: string` with no nullability, so a client generated from
the document would treat a legitimately absent colour as a contract violation.

## Solution

Decide whether a column may have no colour. If yes, mark the field nullable in the schema; if no,
the create path should default it and the null here is a data bug. Check the existing
allow-editing-a-column's-colour todo (2026-09-04) first, since the answer shapes both.

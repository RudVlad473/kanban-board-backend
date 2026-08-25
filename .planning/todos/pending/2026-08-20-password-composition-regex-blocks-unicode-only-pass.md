---
created: 2026-08-20T00:00:00.000Z
title: "Password composition regex blocks Unicode-only passwords and violates ASVS's no-composition-rules guidance"
area: security
severity: moderate
files:

  - src/main/java/com/vrudenko/kanban_board/dto/annotation/Password.java

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V2.1.4, V2.1.9).

`Password.java`'s `@Pattern` regex mandates at least one ASCII lowercase, uppercase, digit, and
special character. This rejects a valid Unicode-only password (e.g. all-Cyrillic or all-emoji with
no ASCII special character) and directly contradicts ASVS's explicit guidance to drop
composition-class requirements in favor of length as the primary strength signal.

## Solution

Remove the `@Pattern` composition constraint entirely from `Password.java`, keeping only the
`@Size` length bounds (see the companion min/max-length todo). Remove or update any test asserting
composition-rule rejection. Add a test proving a sufficiently long Unicode-only password now
passes.

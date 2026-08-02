---
created: 2026-08-02T13:14:00.000Z
title: Add ArchUnit to enforce documented layering and ownership-verification rules
area: tooling
severity: minor
files:
  - build.gradle
  - docs/CODE_STYLE.md
---

## Problem

This codebase already documents strict architectural rules — controller → service → repository layering, and CODE_STYLE.md rule 2's "load entities through the ownership-verified loader, never `repository.findById` directly" — but nothing currently enforces them automatically. They rely entirely on convention and code review, so a violation compiles cleanly and only gets caught if a reviewer happens to notice.

## Solution

Add ArchUnit (runs as regular JUnit tests) once `build.gradle` is unlocked again (locked for the rest of Phase 3). Start with the two rules already documented and enforced by convention:
- Controllers must not directly import/reference repository classes (must go through a service).
- The four domain services (`BoardService`, `ColumnService`, `TaskService`, `SubtaskService`) must not call `repository.findById(id)` directly — only through their own ownership-verified `findById(userId, id)` (CODE_STYLE.md rule 2). `OwnershipVerifierService` and `UserService` are the documented exceptions.

Expand from there as other layering rules solidify.

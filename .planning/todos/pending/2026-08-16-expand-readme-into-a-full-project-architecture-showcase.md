---
created: 2026-08-16T11:03:42.954Z
title: Expand README into a full project architecture showcase
area: docs
severity: minor
resolves_phase: 10
files:
  - README.md
  - docs/ARCHITECTURE.md
  - docs/INFRA_ARCHITECTURE.md
  - docs/diagrams/
---

## Problem

The README is currently intentionally trimmed to a ~116-line front door (see quick task 21,
2026-08-06, "Split the README's depth into a new docs/ARCHITECTURE.md and trim README to a
116-line front door in standard-readme section order; reverses task 20's inline-depth
decision"). Since then the project has accumulated real, demonstrable engineering depth that
isn't visible from the README alone: a scanning/gated pre-commit hook (`.githooks/pre-commit`,
being extended with secret scanning via quick task 260816-hn1), a layered test architecture
(unit/service/controller/e2e tiers, Testcontainers-backed Postgres/Kafka, ArchUnit layering
rules, query-count assertions), a real production deployment (Netcup VM + Neon Postgres +
Redpanda, Plan 05-04), CI quality gates (Spotless, Error Prone, JaCoCo ratchet, OWASP
dependency-check, now secret scanning), and a set of Mermaid architecture diagrams under
`docs/diagrams/`. None of this is currently presented anywhere as a cohesive showcase — a
reader (recruiter, collaborator, future self) has to already know this repo well to find any
of it.

## Solution

TBD — the user wants this content added to README specifically, but that request is in direct
tension with quick task 21's explicit decision to keep README trimmed and push depth into
`docs/ARCHITECTURE.md`/`docs/INFRA_ARCHITECTURE.md`. Whoever picks this up should surface that
tension and get an explicit call rather than silently re-inflating README back to where task 21
un-did it. Two shapes to weigh:
1. Expand README itself (reverses task 21's structure decision) — read task 21's SUMMARY first
   to understand why it split things out before undoing it.
2. Keep README as the trimmed front door, but expand it with a much more prominent,
   scannable "Engineering highlights" section that links out to already-existing (or
   newly-expanded) depth in `docs/ARCHITECTURE.md` / `docs/INFRA_ARCHITECTURE.md` / a new doc —
   preserving the standard-readme convention while still surfacing the showcase content near
   the top.

Either way, cover: pre-commit hooks and CI quality gates (formatting, tests, ArchUnit,
Error Prone, JaCoCo, dependency scanning, secret scanning), testing architecture (tiers, what
runs where, Testcontainers), deployment strategy (the real Netcup/Neon/Redpanda production
stack, how CI ships to it), verification/quality gates in general, the existing
`docs/diagrams/` Mermaid diagrams, how to run the project locally, the technology stack, and
*why* each major technology was chosen (this repo's STATE.md decision log already has most of
this reasoning — Testcontainers over H2, Flyway, Spring Session JDBC, gitleaks over
TruffleHog/detect-secrets, Netcup over Oracle/AWS/GCP/Hetzner, etc. — mine it rather than
re-deriving from scratch).

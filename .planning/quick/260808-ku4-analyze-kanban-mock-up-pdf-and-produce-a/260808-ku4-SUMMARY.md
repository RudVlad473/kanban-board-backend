---
phase: quick-260808-ku4
plan: 01
subsystem: docs
tags: [documentation, gap-analysis, mockup, rest-api]
status: complete

dependency-graph:
  requires: []
  provides:
    - docs/MOCKUP_FEATURE_GAP.md
  affects:
    - future frontend hand-off planning
    - future v2 scoping (board creation route, column deletion, task/column
      reorder, GET /boards/{boardId}/full, theme persistence)

tech-stack:
  added:
    - pypdf 6.15.0 (user-site, pre-installed; used read-only, no build.gradle change)
  patterns:
    - text extraction via pypdf's /ToUnicode CMap resolution for a subsetted-glyph PDF
    - PDF page mediabox (canvas-size) inspection as a structural evidence technique
      when visual rendering is unavailable

key-files:
  created:
    - docs/MOCKUP_FEATURE_GAP.md
    - .planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/extract-mockup-text.py
    - .planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/mockup-pages.txt
  modified: []

decisions:
  - Split the 115MB PDF into small derived PDFs via pypdf to route around the Read
    tool's 100MB size cap, then discovered a second, unconditional blocker
    (no poppler-utils/pdftoppm in this environment) that made visual rendering
    impossible by any method; did not install poppler mid-execution because the
    plan's own threat model commits to zero unattended installs.
  - Used PDF page mediabox dimensions (via the already-sanctioned pypdf) as a
    structural substitute for visual confirmation of device breakpoints — this
    corrected the phase's own planning-time page-range table (tablet/mobile
    boundary is actually at pages 34/54, not 22/34).
  - Kept theming and drag-reorder claims explicitly labeled as unconfirmed /
    lower-confidence in the document rather than asserting them as established
    facts, since no method available in this environment could confirm them.

metrics:
  duration: ~55min
  completed: 2026-08-08
  tasks: 2
  commits: 2
  files: 3

actuals:
  tokens: 41000
  tasks: 2
  commits: 2
---

# Phase quick-260808-ku4 Plan 01: Analyze Kanban Mock-up PDF and Produce a Feature Gap Document Summary

Produced `docs/MOCKUP_FEATURE_GAP.md`, a 9-area, 109-row (66 mock-up + 43 backend)
comparison of the Kanban design mock-ups against the backend's actual REST surface,
backed by a committed full-text extraction of all 73 mock-up pages so every claim
is independently re-checkable without the 115MB source PDF.

## What Was Built

- **`extract-mockup-text.py`** — a `pypdf`-based extractor that reads the PDF's
  73 pages, applies the document's embedded `/ToUnicode` CMaps to resolve
  subsetted glyph indices into real text, and writes one `=== page N ===`-delimited
  block per page.
- **`mockup-pages.txt`** — the complete extraction output: 73 pages, 78,808
  characters, matching the planning-time measurement exactly.
- **`docs/MOCKUP_FEATURE_GAP.md`** — the gap document itself:
  - A provenance header (PDF path, page count, extraction method/date, backend
    commit `a5c36e6`).
  - One shared inventory schema (`ID | Feature Area | Action | Description |
    Source`) used identically by both inventories.
  - Three numbered gap sections: **1.** features the mock-ups imply but the
    backend is missing or incomplete on (6 entries, including the previously-known
    missing board-creation route, plus five findings from this pass: no column
    deletion route, no task/column ordering field, no single nested board read,
    no theme-preference persistence, and subtasks missing the optimistic-locking
    `version` field that every sibling entity has); **2.** backend features the
    mock-ups don't show (the paginated activity log, the `version`
    concurrency-control surface, and the full auth flow — the last two are
    explicitly distinguished as "correctly invisible" vs. "genuinely missing
    screens"); **3.** features present in both, rendered as five per-area
    hand-off maps (screen → endpoint).
  - Two full appendices (mock-up inventory, backend inventory) covering all nine
    required areas: Auth and account, Boards, Columns, Tasks, Subtasks, Task
    movement and status, Navigation and layout, Theming, Activity log.
  - A method-and-limitations appendix disclosing exactly what could and could not
    be confirmed, and how.

## Task 1 — Tracer (Boards area, schema proof)

Wired the whole pipeline (extraction → both inventories → three gap sections →
appendices) and populated it end-to-end for Boards only, to prove the schema
before expanding. Recorded the board-creation gap: no `POST /boards` route exists
anywhere; the only creation path is `UserService.addBoardByUserId`, called from no
controller.

Commit: `e66cf16` — `feat(quick-260808-ku4): prove mockup-vs-backend gap doc
schema on Boards`

## Task 2 — Expansion to all nine areas

Replaced every `PENDING-TASK-2` stub with real content across the remaining eight
areas, cross-checked against all seven `@RestController` classes and their
request/response DTOs (not inherited unchecked from the plan's own findings —
per-DTO field reads confirmed which entities do and don't carry a `version`
field, confirmed `GET /boards` returns no nested columns, confirmed no
theme-preference field exists anywhere, and confirmed the `/logout` route is
declarative rather than a controller method).

Commit: `588ce44` — `feat(quick-260808-ku4): expand mockup gap doc to all nine
feature areas`

## Deviations from Plan

### Auto-fixed / Adapted Issues

**1. [Rule 3 - Blocking issue] Visual PDF reads were impossible in this
environment; substituted a structural technique and disclosed the gap honestly.**
- **Found during:** Task 1, Step 3 (the plan's bounded visual-read step).
- **Issue:** Two independent, unconditional blockers, discovered only at
  execution time:
  1. The Read tool refused any page-range request against the 115MB source PDF
     outright ("PDF file exceeds maximum allowed size for text extraction
     (100MB)") — a hard cap on total file size, not page count.
  2. After working around (1) by using `pypdf.PdfWriter` to extract the needed
     pages into small (<20MB) derived PDFs — the same library already sanctioned
     by the plan's threat model, no new dependency — the Read tool's
     image-rendering path itself required `pdftoppm` (poppler-utils), which is
     not installed in this environment, with no fallback (`pymupdf`,
     `pdf2image`, `Pillow`) available either.
- **Fix:** Did not attempt to install poppler-utils mid-execution — the plan's
  own threat model (T-ku4-SC) explicitly commits to zero unattended installs for
  exactly this class of decision, and a system-level tool install is a strictly
  larger version of that same risk, not a smaller one. Instead used PDF page
  `mediabox` (canvas-size) inspection via the already-approved `pypdf` as a
  structural substitute — this is real, non-visual evidence, not a workaround
  that pretends to be equivalent to visual confirmation. It successfully
  corrected a planning-time assumption (see Known Limitations below) but could
  not resolve theming (page dimensions don't vary by theme) or the drag-reorder
  affordance (no textual signature). Both are explicitly disclosed as
  unconfirmed/lower-confidence in Appendix C and in their respective inventory
  rows, rather than silently omitted or asserted without evidence.
- **Files modified:** `docs/MOCKUP_FEATURE_GAP.md` (Appendix C, MU-N3, MU-Th1..3,
  MU-M3).
- **Commits:** `e66cf16`, `588ce44`.

**2. [Rule 3 - Blocking issue] Docker Desktop was not running, blocking the
repo's pre-commit hook (Testcontainers-backed fast test suite).**
- **Found during:** Task 1 commit attempt.
- **Issue:** `.githooks/pre-commit` runs `spotlessApply` then
  `./gradlew test --exclude-tests '*E2ETest'` on every commit; the fast suite
  uses Testcontainers, which failed to initialize with
  `DockerClientProviderStrategy` errors because the Docker daemon wasn't
  reachable, even though the Docker CLI was installed.
- **Fix:** Started Docker Desktop and polled (single bounded loop, ~10s) until
  the daemon responded, then retried the commit normally — no hook was skipped,
  no `--no-verify` was used, consistent with the "never skip hooks" constraint.
- **Files modified:** none (environment-only).
- **Commits:** both commits proceeded through the full hook once Docker was up.

No other deviations. Both tasks otherwise executed as written, including the
explicit exclusion of `./gradlew spotlessCheck` / `./gradlew test` from this
plan's own verification (the pre-commit hook running them is a separate,
unrelated repo-wide gate, not part of this plan's stated verification).

## Known Limitations (disclosed in the document itself, Appendix C)

- 0 of the mock-up's 73 pages were visually rendered in this environment (see
  Deviation 1 above). All findings are text- or structure-derived.
- Theming (which specific pages are light vs. dark) is inferred from duplicated
  page-pair structure, not confirmed by rendering or by PDF metadata (the PDF
  has no outline/bookmarks).
- The drag-and-drop task/column reorder affordance (MU-M3) is a
  convention-based inference with no textual or structural signature at all,
  and is explicitly labeled lower-confidence in the document rather than
  presented as an observed fact.
- One correction to the phase's own planning-time findings: the true
  desktop/tablet/mobile breakpoint boundaries are pages 34 and 54 (confirmed via
  `mediabox` — 1440×1024 / 768×1024 / 375-wide), not pages 22 and 34 as
  originally stated in the plan's `<planning_findings>`.

## Self-Check: PASSED

- `docs/MOCKUP_FEATURE_GAP.md` — FOUND
- `.planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/extract-mockup-text.py` — FOUND
- `.planning/quick/260808-ku4-analyze-kanban-mock-up-pdf-and-produce-a/mockup-pages.txt` — FOUND
- Commit `e66cf16` — FOUND in `git log --oneline --all`
- Commit `588ce44` — FOUND in `git log --oneline --all`
- `git status --porcelain -- src/` — empty (confirmed after both commits)
- Task 1 `<verify>` command — PASS (re-run against final state)
- Task 2 `<verify>` command — PASS (re-run against final state)

---
phase: quick-260813-jrt
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - README.md
  - .planning/codebase/ARCHITECTURE.md
  - .claude/CLAUDE.md
  - docs/ARCHITECTURE.md
autonomous: true
requirements: [QUICK-260813-jrt]

estimate:
  tokens: 38000
  raw_tokens: 38000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "README.md's API table documents every HTTP route the application actually serves: 16 rows accounting for all 24 method-level @*Mapping annotations across the 7 controllers plus AuthenticationController, plus the filter-based /logout route that has no @*Mapping"
    - "The six routes previously absent from README.md's table — POST /boards, GET /boards/{boardId}/full, DELETE /boards/{boardId}/columns/{columnId}, PATCH /boards/{boardId}/columns/{columnId}/reorder, GET /users/me/theme, PUT /users/me/theme — are each documented"
    - "The paragraph that claimed board creation had no HTTP route and columns had no delete route no longer exists anywhere in README.md"
    - "The 13 table rows that were already present keep their hand-written Notes text unchanged except where a merged method genuinely required extending the note"
    - "README.md's theme row states that the caller's identity comes from the session, so the row cannot be misread as exposing another user's preferences"
    - ".planning/codebase/ARCHITECTURE.md and .claude/CLAUDE.md both name UserController, ActivityController and TaskMoveController, and both list the routes those controllers serve"
    - "The route enumeration in .claude/CLAUDE.md matches the one in .planning/codebase/ARCHITECTURE.md, so the next CLAUDE.md re-assembly cannot revert the fix"
    - "No file under src/ is created, modified or deleted"
  artifacts:
    - README.md
    - .planning/codebase/ARCHITECTURE.md
    - .claude/CLAUDE.md
  key_links:
    - "each @*Mapping annotation in src/main/java/.../controller/ + AuthenticationController -> exactly one method cell in README.md's API table (the parity invariant: 24 annotations -> 16 rows)"
    - ".planning/codebase/ARCHITECTURE.md 'Entry Points' -> the GSD:architecture-start/end block in .claude/CLAUDE.md (source -> assembled mirror; editing only the mirror silently reverts)"
    - "the deleted 'gaps' paragraph -> the two table rows that now document those routes (the paragraph may only go once both rows exist)"
---

<objective>
Rebuild `README.md`'s `## API` table so it matches the routes the application actually
serves, delete the paragraph below it that describes two gaps Phase 6 already closed, and
bring the two cross-check targets named by the source todo into the same state.

Purpose: `README.md` is the front door of the repo and its route table is the only
route-level documentation a reader meets before the source. It currently understates the
API by six routes and then explicitly asserts that two of those six do not exist — a
reader who trusts it concludes the backend is less complete than it is.

Output: an accurate `## API` table, no false gap prose, and controller/route parity in
`.planning/codebase/ARCHITECTURE.md` + `.claude/CLAUDE.md`.
</objective>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.planning/todos/pending/2026-08-10-readme-api-table-missing-routes-shipped-since-phase-6.md

@README.md
@.planning/codebase/ARCHITECTURE.md
@.claude/CLAUDE.md
</context>

<planning_findings>

## The route enumeration was done during planning — these are its results

Every controller was enumerated exhaustively (the source todo warns its own list is not a
systematic audit, and it is right — see "Where the todo was wrong" below). There are
**24 method-level `@*Mapping` annotations** across 8 classes:

| Class | Class-level `@RequestMapping` | Method mappings |
|---|---|---|
| `BoardController` | `/boards` | `GET` ·, `POST` ·, `DELETE {boardId}`, `PUT {boardId}`, `POST {boardId}/columns`, `GET {boardId}/full` |
| `ColumnController` | `/boards/{boardId}/columns` | `GET` ·, `POST {columnId}`, `PUT {columnId}`, `DELETE {columnId}`, `PATCH {columnId}/reorder` |
| `TaskController` | `/boards/{boardId}/columns/{columnId}/tasks` | `GET` ·, `DELETE {taskId}`, `PUT {taskId}`, `POST {taskId}/subtasks` |
| `SubtaskController` | `…/tasks/{taskId}/subtasks` | `GET` ·, `DELETE {subtaskId}`, `PUT {subtaskId}` |
| `TaskMoveController` | `/tasks` | `PATCH {taskId}/move` |
| `UserController` | `/users` | `GET /me/theme`, `PUT /me/theme` |
| `ActivityController` | `/boards/{boardId}/activity` | `GET` · |
| `AuthenticationController` | (none) | `POST /signin`, `POST /signup` |

Plus `/logout`, which is served by the Spring Security filter chain
(`SecurityConfiguration` + `LogoutHandler`) and therefore carries no `@*Mapping` — it is
already in the README table and stays.

All routes sit under context path `/api` (`server.servlet.context-path=/api`).

**The six routes missing from README.md's current 13-row table:**

1. `POST /boards` — `BoardController.save` → `userService.addBoardByUserId`; returns `201` + `Location` (GAP-01)
2. `GET /boards/{boardId}/full` — `BoardController.findFullById` → `BoardFullResponseDTO` (GAP-04)
3. `DELETE /boards/{boardId}/columns/{columnId}` — `ColumnController.deleteById` (GAP-02)
4. `PATCH /boards/{boardId}/columns/{columnId}/reorder` — `ColumnController.reorder`; `ReorderColumnRequestDTO` carries `@NotNull Long version` + `@NotNull @Min(0) Integer targetPosition` (GAP-03)
5. `GET /users/me/theme` — `UserController.getTheme` (GAP-05)
6. `PUT /users/me/theme` — `UserController.updateTheme`; `UpdateThemeRequestDTO` carries `@NotNull ThemePreference theme`, no version (GAP-05)

## Where the todo was wrong — do not propagate its errors

- It says "the task/column **reorder endpoints**" (plural). There is exactly **one**
  reorder route, and it reorders a **column**: `PATCH /boards/{boardId}/columns/{columnId}/reorder`.
  Task repositioning is not a reorder route — it happens through the existing
  `PATCH /tasks/{taskId}/move` via that DTO's `targetPosition`. Do not invent a task-reorder row.
- It implies the table may also be missing `PATCH /tasks/{taskId}/move` and
  `GET /boards/{boardId}/activity`. **Both are already present and correct** (README lines 78 and
  81) — they were added by the later v1.1/v1.2 work. Leave them alone.
- `UserController.getTheme`/`updateTheme` take **no user id** from path or body; identity
  comes from the session. Its Javadoc calls this the whole IDOR mitigation and says it is
  structural. The README row must not be written in a way that suggests a user-id path segment.

## Cross-check target 1: docs/ARCHITECTURE.md — essentially clean, one narrow fix

It has **no route table and no stale gap prose**. Every route it names in its sequence
diagrams is accurate: `/api/signin`, `/api/signup`, `PATCH /tasks/{id}/move`,
`GET /boards/{boardId}/activity`, `/users/me`, `PUT /tasks/{id}`. It already knows about
the nested read (it discusses `BoardFullResponseDTO` at line ~188). **Report it as accurate
rather than inventing changes**, with one exception:

Lines ~185-187 enumerate the DTOs that require the client to echo the version it read —
`UpdateBoardRequestDTO`, `UpdateTaskRequestDTO`, `MoveTaskRequestDTO`, `UpdateColumnRequestDTO`,
`UpdateSubtaskRequestDTO`. `ReorderColumnRequestDTO` also carries `@NotNull Long version` and is
absent from that list. This is the identical failure mode the todo is about (a Phase 6 GAP-03
artifact missing from a doc enumeration) and it is a one-entry edit, so it is in scope. Nothing
else in that file changes.

## Cross-check target 2: .claude/CLAUDE.md — stale, and it has an assembly trap

`.claude/CLAUDE.md` lines 346-350 carry a route list that predates Phase 6 (no theme routes,
no `/full`, no `/activity`, no `/move`, no column delete, no reorder), and its Component
Responsibilities table omits `UserController`, `ActivityController` and `TaskMoveController`.

**The trap:** those lines sit inside
`<!-- GSD:architecture-start source:ARCHITECTURE.md -->` … `<!-- GSD:architecture-end -->`
(CLAUDE.md lines 217-387). That block is *assembled* from
`.planning/codebase/ARCHITECTURE.md` (Entry Points at line ~290, Component Responsibilities
at line ~92). Editing `.claude/CLAUDE.md` alone produces a change that looks correct and is
silently reverted the next time CLAUDE.md is re-assembled. **Both files must be edited in
lockstep, source first.**

The assembly strips the per-item `- src/main/java/...` sub-bullets and the
`**HTTP Controller Entry Points:**` sub-heading, so the two blocks are not byte-identical by
design — parity is asserted on content (controller names and route literals), not on bytes.

</planning_findings>

<approach>

## Alternatives considered

**A — Full regeneration.** Delete the table and re-emit all 16 rows from the enumeration above.

**B — Additive patch (picked).** Insert the 3 genuinely-new rows and merge 2 methods into
existing rows; leave the other 11 rows byte-identical.

**C — Derive from the OpenAPI document.** springdoc-openapi 2.8.8 is on the classpath and
emits the full route set; generate the table from `openapi.json`.

## Trade-off matrix

| Approach | Pros / Cons | Why picked / rejected |
|---|---|---|
| **A — Full regeneration** | **+** Guarantees no stale row survives; uniform tone. **−** Destroys hand-written editorial Notes that are not derivable from source ("max 2 concurrent sessions per user", "delete cascades to columns, tasks, subtasks", "default 20, capped at 100", the `…` path-elision convention). Those notes are the table's actual value over generated docs, and re-deriving them means re-reading `SecurityConfiguration`, the cascade config and the pageable properties — turning a doc edit into a re-audit. | **Rejected.** High blast radius for zero information gain: the enumeration already proved all 13 existing rows are accurate. |
| **B — Additive patch** | **+** Smallest reviewable diff; every surviving row is one the enumeration verified; editorial Notes preserved for free. **−** Only sound *because* an exhaustive enumeration was done first — done blind it would silently inherit whatever the old table got wrong. | **Picked.** The enumeration (see `<planning_findings>`) is what makes the additive path safe, and it is already complete. The 24-annotation → 16-row parity gate independently re-proves completeness, so the "inherits old errors" failure mode is closed by verification rather than by rewriting. |
| **C — OpenAPI-derived** | **+** Mechanically cannot miss a route. **−** `openapi.json` is deleted in the working tree; regenerating it means booting the app + Postgres (Testcontainers/Docker) for a docs-only change. Output has no editorial Notes and no path-elision convention, so it needs the same hand-editing anyway — the generator only replaces the enumeration step, which is already done. | **Rejected.** Cost is a full app boot; benefit is a step already completed by grep in seconds. |

## Non-obvious trade-offs

- **State-invalidation risk (the real hazard here).** `.claude/CLAUDE.md`'s route list is an
  assembled mirror, not a source. A correct-looking edit to it alone is a no-op with a delay
  fuse: it survives until the next `/gsd-docs-update`, then reverts, and the todo's cross-check
  looks done while the staleness returns. Mitigated by editing `.planning/codebase/ARCHITECTURE.md`
  first and gating on both files.
- **No performance or memory dimension** — every changed file is Markdown, no source under
  `src/` is touched, no runtime behaviour moves. Time complexity of the change is the length
  of the table.
- **Verification cannot lean on the build.** `./gradlew spotlessCheck` and `./gradlew test`
  target `src/**/*.java` and prove nothing about a Markdown edit (they would pass identically
  on a completely wrong table, and cost a Docker/Testcontainers boot to say so). The gates are
  therefore grep-based parity checks against the controllers, which is the only thing that can
  actually fail here. A `git diff --stat` gate covers the "no source touched" claim instead.
- **Security-adjacent wording, not a security change.** The theme row documents a
  deliberately identity-from-session route. Writing it as `/users/{userId}/theme`, or omitting
  why there is no id in the path, documents an IDOR that the code specifically does not have and
  invites someone to "fix" the route to match the docs. The row states the session-identity fact.

</approach>

<tasks>

<task type="auto">
  <name>Task 1: Rebuild README.md's API table and drop the false gap paragraph</name>
  <files>README.md</files>
  <action>
    Edit the `## API` section (currently lines 62-85). The intro sentence at lines 64-65 is
    accurate — leave it unchanged.

    Rebuild the table body to exactly these 16 data rows, in this order. Rows marked
    UNCHANGED must keep their existing text byte-for-byte; do not retouch their Notes.

    1. UNCHANGED — `| `POST` | `/signup` · `/signin` · `/logout` | …existing note… |`
    2. CHANGED — method cell becomes `` `GET` `POST` `` on path `` `/boards` ``; Notes become:
       `GET` lists boards owned by the caller; `POST` creates one — `201` with a `Location`
       header, and the name must be unique for that user.
    3. UNCHANGED — `` `PUT` `DELETE` `` on `` `/boards/{boardId}` ``
    4. NEW — `` `GET` `` on `` `/boards/{boardId}/full` ``; Notes: the board with its columns,
       each column with its tasks, and each task with its subtasks, in one nested document;
       carries the board's own `version`.
    5. UNCHANGED — `` `GET` `` on `` `/boards/{boardId}/columns` ``
    6. UNCHANGED — `` `POST` `` on `` `/boards/{boardId}/columns` `` (Create a column)
    7. CHANGED — method cell becomes `` `PUT` `DELETE` `` on
       `` `/boards/{boardId}/columns/{columnId}` ``; extend the existing note so it still says
       `PUT` requires the current `version`, and add that `DELETE` cascades to the column's
       tasks and subtasks.
    8. UNCHANGED — `` `POST` `` on `` `/boards/{boardId}/columns/{columnId}` `` (Create a task in the column)
    9. NEW — `` `PATCH` `` on `` `/boards/{boardId}/columns/{columnId}/reorder` ``; Notes:
       reposition a column within its board; body takes `targetPosition` and requires the
       current `version`.
    10. UNCHANGED — `` `GET` `` on `` `…/columns/{columnId}/tasks` ``
    11. UNCHANGED — `` `PUT` `DELETE` `` on `` `…/columns/{columnId}/tasks/{taskId}` ``
    12. UNCHANGED — `` `PATCH` `` on `` `/tasks/{taskId}/move` ``
    13. UNCHANGED — `` `GET` `POST` `` on `` `…/tasks/{taskId}/subtasks` ``
    14. UNCHANGED — `` `PUT` `DELETE` `` on `` `…/tasks/{taskId}/subtasks/{subtaskId}` ``
    15. UNCHANGED — `` `GET` `` on `` `/boards/{boardId}/activity` ``
    16. NEW — `` `GET` `PUT` `` on `` `/users/me/theme` ``; Notes: the caller's own theme
        preference (`LIGHT`/`DARK`); the user is taken from the session, so no user id appears
        in the path.

    Formatting must match the table already there: three columns `Method | Path | Notes`,
    separator `|---|---|---|`, every method and path wrapped in backticks, multiple methods on
    one row separated by a space, deep paths elided with the leading `…` exactly as rows 10-14
    already do, and an empty Notes cell written as `| |` where there is nothing to say.

    Then delete the three-line paragraph that sits between the table and `## Testing`
    (currently lines 83-85) in full, along with the blank line that separated it from the
    table, so `## Testing` follows the table after a single blank line. That paragraph asserts
    that board creation is unreachable over HTTP and that columns cannot be deleted; rows 2 and
    7 now document both routes, so the paragraph is not merely redundant but false. Do not
    replace it with anything — no "previously missing" note, no changelog sentence. The table
    is the statement.

    Nothing outside lines 62-85 changes. Leave `## Testing`, `## Project status` and
    `## Documentation` untouched.
  </action>
  <verify>
    <automated>
      cd "$(git rev-parse --show-toplevel)" &amp;&amp;
      ROWS=$(awk '/^## API$/{f=1} /^## Testing$/{f=0} f' README.md | grep -c '^|') &amp;&amp;
      MAPS=$(grep -rhoE '@(Get|Post|Put|Patch|Delete)Mapping' src/main/java/com/vrudenko/kanban_board/controller src/main/java/com/vrudenko/kanban_board/security | wc -l) &amp;&amp;
      STALE=$(grep -c 'addBoardByUserId' README.md || true) &amp;&amp;
      SRC=$(git diff --name-only -- src/ | wc -l) &amp;&amp;
      FAIL=0 &amp;&amp;
      [ "$ROWS" -eq 18 ] || { echo "FAIL table rows: got $ROWS pipe-lines, want 18 (16 data + header + separator)"; FAIL=1; } &amp;&amp;
      [ "$MAPS" -eq 24 ] || { echo "FAIL controller drift: $MAPS mappings, plan assumed 24 -- re-derive before trusting the table"; FAIL=1; } &amp;&amp;
      [ "$STALE" -eq 0 ] || { echo "FAIL stale gap paragraph still present ($STALE hit(s))"; FAIL=1; } &amp;&amp;
      [ "$SRC" -eq 0 ] || { echo "FAIL docs-only violated: $SRC file(s) under src/ modified"; FAIL=1; } &amp;&amp;
      for p in '/boards/{boardId}/full' '/boards/{boardId}/columns/{columnId}/reorder' '/users/me/theme' '/tasks/{taskId}/move' '/boards/{boardId}/activity'; do
        grep -qF "$p" README.md || { echo "FAIL route absent from README: $p"; FAIL=1; }
      done &amp;&amp;
      grep -qF '`GET` `POST` | `/boards`' README.md || { echo "FAIL POST /boards row not merged onto the /boards row"; FAIL=1; } &amp;&amp;
      grep -qF '`PUT` `DELETE` | `/boards/{boardId}/columns/{columnId}`' README.md || { echo "FAIL DELETE column not merged onto the column PUT row"; FAIL=1; } &amp;&amp;
      [ "$FAIL" -eq 0 ] &amp;&amp; echo "PASS README API table parity: 16 rows cover 24 mappings + filter-based /logout"
    </automated>
  </verify>
  <done>
    README.md's API table has 16 data rows covering all 24 method-level `@*Mapping`
    annotations plus `/logout`; all six previously-absent routes appear; the paragraph
    claiming board creation and column deletion are unexposed is gone with nothing in its
    place; the 11 untouched rows are byte-identical to before; no file under `src/` changed.
  </done>
</task>

<task type="auto">
  <name>Task 2: Bring the cross-check targets to the same parity, source before mirror</name>
  <files>.planning/codebase/ARCHITECTURE.md, .claude/CLAUDE.md, docs/ARCHITECTURE.md</files>
  <action>
    Order matters — edit `.planning/codebase/ARCHITECTURE.md` first, because
    `.claude/CLAUDE.md`'s copy is assembled from it and an edit made only to the mirror is
    reverted at the next re-assembly (see `<planning_findings>`).

    **Step 1 — `.planning/codebase/ARCHITECTURE.md`.**

    In the Component Responsibilities table (around line 92), add three rows in the existing
    `| Component | Responsibility | File |` shape, placed with the other controllers:
    - `UserController` — HTTP endpoints for the caller's own theme preference
    - `TaskMoveController` — HTTP endpoint for cross-column task moves
    - `ActivityController` — HTTP endpoint for a board's paginated activity feed

    In the `**HTTP Controller Entry Points:**` list (around line 297), rewrite the five bullets
    to cover every route, keeping the existing bullet shape exactly — a bolded operation-group
    label, the `/api`-prefixed paths, then an indented sub-bullet with the controller's source
    path. Cover: board GET/POST list-and-create, board PUT/DELETE by id, the nested full read,
    board column creation, column GET/PUT/DELETE plus reorder, task GET/PUT/DELETE plus subtask
    creation, subtask GET/PUT/DELETE, the flat task move route, the board activity feed, the
    two theme routes, and the three authentication routes. Take the paths from the table in
    `<planning_findings>` — do not re-derive them by hand.

    **Step 2 — `.claude/CLAUDE.md`.**

    Mirror both edits into the `<!-- GSD:architecture-start source:ARCHITECTURE.md -->` …
    `<!-- GSD:architecture-end -->` block (Component Responsibilities around line 92 of that
    block, Entry Points at lines 346-350). Match how the assembly already renders that block:
    the `**HTTP Controller Entry Points:**` sub-heading and the per-bullet source-path
    sub-bullets are stripped there, so write the route bullets flat, exactly as the five
    existing ones are. Do not move, add or remove any `<!-- GSD:* -->` marker.

    **Step 3 — `docs/ARCHITECTURE.md`.**

    This file's route mentions were checked during planning and are accurate — it has no route
    table and no gap prose, so it gets no route edits. Make exactly one change: in the
    optimistic-locking bullet around lines 185-187 that lists the DTOs requiring the client to
    echo the version it read, add `ReorderColumnRequestDTO` to that list (it carries
    `@NotNull Long version` and was omitted). Keep the sentence's existing grammar — it is a
    comma list ending in "and", so the conjunction moves to the new final entry. Change nothing
    else in this file; in particular leave the `BoardFullResponseDTO` sentence that follows it
    alone, since it is already correct.

    Record in the SUMMARY that `docs/ARCHITECTURE.md` was audited for the todo's staleness and
    found accurate apart from that one DTO-list entry, so a later reader does not re-open it.
  </action>
  <verify>
    <automated>
      cd "$(git rev-parse --show-toplevel)" &amp;&amp;
      FAIL=0 &amp;&amp;
      for n in UserController ActivityController TaskMoveController; do
        for f in .planning/codebase/ARCHITECTURE.md .claude/CLAUDE.md; do
          grep -qF "$n" "$f" || { echo "FAIL $n absent from $f"; FAIL=1; }
        done
      done &amp;&amp;
      for p in '/users/me/theme' '/boards/{boardId}/full' '/reorder' '/tasks/{taskId}/move' '/boards/{boardId}/activity'; do
        for f in .planning/codebase/ARCHITECTURE.md .claude/CLAUDE.md; do
          grep -qF "$p" "$f" || { echo "FAIL route $p absent from $f"; FAIL=1; }
        done
      done &amp;&amp;
      MARKERS=$(grep -c 'GSD:architecture-start\|GSD:architecture-end' .claude/CLAUDE.md) &amp;&amp;
      [ "$MARKERS" -eq 2 ] || { echo "FAIL assembly markers disturbed: found $MARKERS, want 2"; FAIL=1; } &amp;&amp;
      grep -qF 'ReorderColumnRequestDTO' docs/ARCHITECTURE.md || { echo "FAIL ReorderColumnRequestDTO missing from docs/ARCHITECTURE.md version-DTO list"; FAIL=1; } &amp;&amp;
      SRC=$(git diff --name-only -- src/ | wc -l) &amp;&amp;
      [ "$SRC" -eq 0 ] || { echo "FAIL docs-only violated: $SRC file(s) under src/ modified"; FAIL=1; } &amp;&amp;
      [ "$FAIL" -eq 0 ] &amp;&amp; echo "PASS cross-check parity: source and assembled mirror agree, markers intact"
    </automated>
  </verify>
  <done>
    `.planning/codebase/ARCHITECTURE.md` and `.claude/CLAUDE.md` both name all seven
    controllers plus `AuthenticationController` and enumerate every route including the theme,
    full-read, reorder, move and activity routes; the two `GSD:architecture-*` markers are
    intact and the mirror matches its source, so re-assembly is a no-op;
    `docs/ARCHITECTURE.md` differs only by the added `ReorderColumnRequestDTO` entry; no file
    under `src/` changed.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| repo reader → documented API surface | A reader (human or agent) acts on the README table as if it described the real routes |
| doc source → assembled `.claude/CLAUDE.md` | Content crosses a generation step that can overwrite hand edits |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-jrt-01 | Information disclosure | README theme row | low | mitigate | Row states identity comes from the session and shows no user-id path segment, so it cannot be read as `/users/{userId}/theme`; `UserController`'s structural IDOR mitigation is not misdocumented as an id-bearing route |
| T-jrt-02 | Tampering | `.claude/CLAUDE.md` assembled block | medium | mitigate | Source `.planning/codebase/ARCHITECTURE.md` edited first and both gated; marker count asserted at 2 so the assembly boundary is not moved or removed |
| T-jrt-03 | Repudiation | README route table | medium | mitigate | 24-annotation → 16-row parity gate re-derives from source at verify time, so a table that drifts from the controllers fails rather than passing silently |
| T-jrt-04 | Tampering | source tree | low | mitigate | `git diff --name-only -- src/` gated at 0 in both tasks; a docs task that touched Java fails |
| T-jrt-SC | Tampering | package installs | low | accept | No package-manager install occurs — this change is Markdown only, no dependency is added or resolved |
</threat_model>

<verification>
- `awk '/^## API$/{f=1} /^## Testing$/{f=0} f' README.md | grep -c '^|'` returns 18.
- `grep -rhoE '@(Get|Post|Put|Patch|Delete)Mapping' src/main/java/com/vrudenko/kanban_board/controller src/main/java/com/vrudenko/kanban_board/security | wc -l` returns 24, confirming the table was built against the controller set the plan enumerated and not a drifted one.
- `grep -c 'addBoardByUserId' README.md` returns 0.
- `git diff --name-only -- src/` is empty.
- `git diff --stat` lists at most `README.md`, `.planning/codebase/ARCHITECTURE.md`, `.claude/CLAUDE.md`, `docs/ARCHITECTURE.md`.
- `./gradlew spotlessCheck` / `./gradlew test` are deliberately NOT gates here: they read `src/**/*.java`, which this change does not touch, so they would pass regardless of whether the table is right and cost a Testcontainers boot to say nothing.
</verification>

<success_criteria>
- README.md's API table documents all 24 method-level mappings plus `/logout` in 16 rows.
- All six previously-missing routes are documented; no invented task-reorder route appears.
- The false gap paragraph is gone, with nothing written in its place.
- The 11 unchanged rows kept their hand-written Notes verbatim.
- `.planning/codebase/ARCHITECTURE.md` and `.claude/CLAUDE.md` agree and both cover all eight controllers.
- `docs/ARCHITECTURE.md` changed by exactly one DTO-list entry, with its audit result recorded in the SUMMARY.
- The source todo is moved from `.planning/todos/pending/` to `.planning/todos/completed/`.
</success_criteria>

<output>
Create `.planning/quick/260813-jrt-rebuild-readme-md-s-stale-api-table-miss/260813-jrt-SUMMARY.md` when done.
</output>

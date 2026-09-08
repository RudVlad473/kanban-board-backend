---
phase: quick-260908-mtl
plan: 01
type: execute
wave: 1
depends_on: []
autonomous: true
requirements: [QUICK-260908-mtl]
files_modified:
  - docker/grafana/provisioning/dashboards/json/node-exporter-full.json
  - docker/grafana/provisioning/dashboards/json/cadvisor.json
  - docker/grafana/provisioning/dashboards/json/postgres-exporter.json
  - .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md

estimate:
  tokens: 22000
  raw_tokens: 22000
  tasks: 2
  confidence: low

must_haves:
  truths:
    - "Each of the three vendored dashboards carries a human-readable display title a non-operator can scan: VM Host Metrics, Per-Container Resource Usage, Postgres Internals."
    - "Each dashboard's `description` still carries its grafana.com ID, revision and fetch date byte-for-byte unchanged, so the provenance trail 12-04 created and `dashboards.yaml` points at survives the rename."
    - "Each dashboard's `uid` is unchanged, so Grafana updates the existing dashboard in place on its next provisioning poll rather than creating a duplicate alongside the old one."
    - "All three JSON files still parse as valid JSON."
    - "The todo file records piece 1 as closed and piece 2 as still open, and remains in `pending/` — matching this repo's existing re-scoped-in-place convention rather than a new one."
  artifacts:
    - docker/grafana/provisioning/dashboards/json/node-exporter-full.json
    - docker/grafana/provisioning/dashboards/json/cadvisor.json
    - docker/grafana/provisioning/dashboards/json/postgres-exporter.json
    - .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md
  key_links:
    - "dashboard JSON `uid` -> Grafana file provisioner: the provisioner keys a dashboard by uid, not by title or filename. Holding uid constant is what makes this an in-place rename instead of a second dashboard appearing next to the first."
    - "dashboard JSON `description` -> docker/grafana/provisioning/dashboards/dashboards.yaml: that file's header comment tells a future reader to look in each JSON's own `description` for the dashboard ID/revision/fetch date. Editing a description would silently break the only provenance record in the deployed artifact."
    - "`.planning/phases/12-*` artifacts -> upstream titles: those files record what was vendored and when. They must NOT be rewritten to the new names; a historical record that changes with hindsight stops being evidence."
---

<objective>
Rename the display `title` of the three Grafana dashboards vendored in phase 12 (plan 12-04) from
their upstream authors' generic names to names a first-time viewer — a portfolio reviewer with
thirty seconds — understands without knowing which exporter produced them.

This closes **piece 1 only** of
`.planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md`. Piece 2
of that todo (exposing selected dashboards through Grafana's public-dashboard feature) is
explicitly OUT OF SCOPE and must remain pending, because publishing genuinely makes the underlying
data public and needs its own panel-by-panel disclosure review.

Purpose: make the observability stack legible to someone who did not build it.
Output: three one-line JSON edits, plus the todo re-scoped in place to piece 2.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
</context>

<design_rationale>

## Alternate approaches considered

**Approach A (chosen) — edit the `title` field in the committed JSON, let the file provisioner
reapply it.** The dashboards are provisioned from a local path
(`docker/grafana/provisioning/dashboards/dashboards.yaml`, `type: file`,
`updateIntervalSeconds: 30`). Changing the committed JSON is the single source of truth; Grafana
re-reads the directory on its poll interval and updates the dashboard whose `uid` matches.

**Approach B — rename through the live Grafana UI or HTTP API on the VM.** A dashboard owned by a
file provider is not editable from the UI (`allowUiUpdates` is unset, therefore false), and even
if the change were forced through the API, the provisioner re-reads the file every 30 seconds and
reverts it. The rename would also live only on one VM's disk, absent from git, and would vanish on
the next container recreate.

**Approach C — leave the JSON untouched and override the title in provisioning config.** Grafana's
file provider exposes `name`, `folder`, `path` and poll settings; it has no per-dashboard title
override. Achieving this would need a build- or start-time `jq` transform over the JSON, which adds
a moving part to container startup — the same class of start-time dependency plan 12-04 explicitly
rejected when it chose to commit pre-fetched JSON over fetching by ID at runtime.

## Trade-off matrix

| Approach | Pros / Cons | Why picked |
|---|---|---|
| **A. Edit committed JSON** | **+** Single source of truth, in git, survives container recreate and redeploy. **+** No new tooling, no Compose change. **+** Diff is one line per file, trivially reviewable. **−** Widens the delta between the vendored copy and its upstream original, so a future re-fetch of a newer revision must re-apply the rename by hand. | Chosen. The one cost is a small, documented, one-line re-apply on a future upstream bump — and the `description` field, preserved verbatim here, is exactly what tells that future reader which upstream revision to diff against. |
| **B. Live UI / API rename** | **+** Immediate, no deploy. **−** Blocked: file-provisioned dashboards are read-only in the UI. **−** Reverted within 30s by the provisioner even via API. **−** Not in git; lost on recreate. | Rejected: does not survive the provisioner, and is invisible to review. |
| **C. Provisioning-config override** | **+** Leaves vendored JSON pristine, so an upstream re-fetch is a clean overwrite. **−** No such config key exists; needs a `jq` transform at container start. **−** Adds a startup dependency and a second place a title can come from. | Rejected: buys pristine-vendor tidiness at the price of a new startup moving part, for a three-line cosmetic change. |

## Non-obvious trade-offs

- **Dashboard identity survives, saved links keep working — but the slug in an old URL does not
  get rewritten.** Grafana addresses a dashboard by `uid`, and its URL is `/d/<uid>/<slug>` where
  the slug is *derived* from the title at creation/update time. Holding all three `uid` values
  unchanged means the provisioner updates the existing row rather than creating a second dashboard
  beside the old one, and any previously-saved or starred URL still resolves: Grafana matches
  purely on the `uid` segment and ignores whatever slug follows it. Confirmed by reproducing this
  exact scenario live (fresh `grafana/grafana:13.2.1`, provisioned, renamed, waited past the 30s
  poll) — requesting the pre-rename URL afterward returned `HTTP/1.1 200 OK` with zero redirects,
  the stale slug still sitting in the address bar. So the functional guarantee (nothing breaks) is
  real, but Grafana never actively rewrites an already-bookmarked URL to the new slug; only a fresh
  navigation from within Grafana's own UI produces a link carrying the current one. This is the
  single thing most likely to be feared about a dashboard rename, and it is why `uid` is a
  must-have truth rather than an assumption.
- **The `description` field is load-bearing provenance, not decoration.**
  `dashboards.yaml`'s header comment instructs a future maintainer to read each JSON's own
  `description` for that dashboard's grafana.com ID, revision and fetch date. It is the only record
  of that inside the deployed artifact. An edit there would be an invisible loss, so the task
  verifies preservation positively (the exact provenance strings still grep) rather than trusting
  care.
- **Blast radius is genuinely three lines.** No CI workflow, no script under `scripts/`, and no
  file under `docs/` refers to any of the three titles. The only references outside the JSON are
  in `.planning/phases/12-self-hosted-observability-stack/` (RESEARCH, PLAN, SUMMARY, REVIEW) —
  historical records of what was vendored, which must stay as written.
- **Performance / memory: none.** A title string does not change panel count, query shape or scrape
  load. Phase 12's measured memory ladders for grafana are unaffected.
- **Security: none in scope — and that is precisely why piece 2 is excluded.** Titles carry no
  hostnames or IPs. The data-disclosure question (panels revealing real hostnames, internal IPs,
  container names) belongs to the public-link work, where the underlying data actually becomes
  public rather than merely link-obscured.

</design_rationale>

<tasks>

<task type="tracer">
  <name>Task 1: Rename all three dashboard display titles, preserving uid and description</name>
  <files>
    docker/grafana/provisioning/dashboards/json/node-exporter-full.json
    docker/grafana/provisioning/dashboards/json/cadvisor.json
    docker/grafana/provisioning/dashboards/json/postgres-exporter.json
  </files>
  <read_first>
    docker/grafana/provisioning/dashboards/dashboards.yaml — the provider that consumes these
    files; its header comment states the description-holds-provenance contract this task must not
    break.
  </read_first>
  <action>
<!-- planner-discipline-allow: Node Exporter Full -->
<!-- planner-discipline-allow: Cadvisor exporter -->
<!-- planner-discipline-allow: PostgreSQL Exporter -->
Make exactly one single-line edit per file, replacing only the dashboard-level `title` value.

Each old title string occurs EXACTLY ONCE in its file (confirmed at planning time), and the
dashboard-level key sits at two-space indentation, distinguishing it from the many panel-level
`"title"` keys nested deeper. So an exact-string Edit on the full line is unambiguous — do not
attempt a broader find/replace.

The three edits, each replacing the whole line including its leading two spaces and trailing comma:

- `docker/grafana/provisioning/dashboards/json/node-exporter-full.json` (line 15531):
  `  "title": "Node Exporter Full",` becomes `  "title": "VM Host Metrics",`
- `docker/grafana/provisioning/dashboards/json/cadvisor.json` (line 777):
  `  "title": "Cadvisor exporter",` becomes `  "title": "Per-Container Resource Usage",`
- `docker/grafana/provisioning/dashboards/json/postgres-exporter.json` (line 3584):
  `  "title": "PostgreSQL Exporter",` becomes `  "title": "Postgres Internals",`

Do NOT touch anything else in these files. Specifically leave unchanged: the adjacent `uid` line
(dashboard identity — see the design rationale), the adjacent `description` line (grafana.com
provenance), `version`, `gnetId`, and every nested panel `title`. Panel titles are the upstream
author's vocabulary for individual charts and are out of scope; only the dashboard's own display
name changes.

Line numbers above are a planning-time convenience for locating the key — match on the string, not
the number.
  </action>
  <verify>
    <automated>
for f in node-exporter-full cadvisor postgres-exporter; do python3 -m json.tool "docker/grafana/provisioning/dashboards/json/$f.json" > /dev/null || { echo "FAIL: $f.json is not valid JSON"; exit 1; }; done; echo "OK: all three parse"
    </automated>
    <automated>
test "$(rg -c '^  "title": "VM Host Metrics",$' docker/grafana/provisioning/dashboards/json/node-exporter-full.json)" = 1 && test "$(rg -c '^  "title": "Per-Container Resource Usage",$' docker/grafana/provisioning/dashboards/json/cadvisor.json)" = 1 && test "$(rg -c '^  "title": "Postgres Internals",$' docker/grafana/provisioning/dashboards/json/postgres-exporter.json)" = 1 && echo "OK: three new titles present at dashboard level"
    </automated>
    <automated>
rg -q 'Node Exporter Full' docker/grafana/provisioning/dashboards/json/node-exporter-full.json && { echo "FAIL: old title still present"; exit 1; }; rg -q 'Cadvisor exporter' docker/grafana/provisioning/dashboards/json/cadvisor.json && { echo "FAIL: old title still present"; exit 1; }; rg -q 'PostgreSQL Exporter' docker/grafana/provisioning/dashboards/json/postgres-exporter.json && { echo "FAIL: old title still present"; exit 1; }; echo "OK: no old titles remain"
    </automated>
    <automated>
test "$(rg -c 'grafana.com dashboard ID 1860, revision 45, fetched 2026-09-07' docker/grafana/provisioning/dashboards/json/node-exporter-full.json)" = 1 && test "$(rg -c "grafana.com dashboard ID 14282, revision 1, fetched 2026-09-07" docker/grafana/provisioning/dashboards/json/cadvisor.json)" = 1 && test "$(rg -c "grafana.com dashboard ID 12485, revision 1, fetched 2026-09-07" docker/grafana/provisioning/dashboards/json/postgres-exporter.json)" = 1 && echo "OK: all three provenance strings intact"
    </automated>
    <automated>
test "$(rg -c '^  "uid": "rYdddlPWk",$' docker/grafana/provisioning/dashboards/json/node-exporter-full.json)" = 1 && test "$(rg -c '^  "uid": "pMEd7m0Mz",$' docker/grafana/provisioning/dashboards/json/cadvisor.json)" = 1 && test "$(rg -c '^  "uid": "v5ciIbUZz",$' docker/grafana/provisioning/dashboards/json/postgres-exporter.json)" = 1 && echo "OK: all three uids unchanged"
    </automated>
    <automated>
NUMSTAT=$(git diff --numstat -- docker/grafana/provisioning/dashboards/json/) || { echo "FAIL: git diff did not run"; exit 1; }; printf '%s\n' "$NUMSTAT" | awk 'NF{n++; if($1!=1||$2!=1) bad++} END{if(n==3 && bad+0==0) print "OK: exactly 3 files, each exactly 1 insertion + 1 deletion"; else {printf "FAIL: changed-file count %d (want 3), files with more than one changed line %d (want 0)\n", n+0, bad+0; exit 1}}'
    </automated>
  </verify>
  <done>
    All three dashboards carry the new display title; all three still parse as JSON; all three
    `uid` and `description` values are byte-identical to before; and the whole change is exactly
    three files at one changed line each.

    Run the final (`git diff --numstat`) gate BEFORE committing this task — it reads the working
    tree. If the task is already committed, the equivalent post-commit form is
    `git diff --numstat HEAD~1 HEAD -- docker/grafana/provisioning/dashboards/json/`.
  </done>
</task>

<task type="auto">
  <name>Task 2: Re-scope the todo in place — piece 1 closed, piece 2 still pending</name>
  <files>.planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md</files>
  <read_first>
    `.planning/todos/pending/2026-08-20-no-remote-log-shipping-structured-logging-or-alerting.md`
    — this repo's established precedent for a partially-closed todo. Read its
    `## Partial resolution (Phase 12)` section and its closing paragraph and follow that shape;
    do not invent a different one. A second, consistent precedent (a `## Resolution` section that
    ends by stating the file is "left in `pending/`, re-scoped in place rather than moved to
    `completed/`") is in
    `.planning/todos/pending/2026-08-20-no-documented-backup-restore-runbook-for-prod-db.md`.
  </read_first>
  <action>
Append a dated partial-resolution section to the todo. Do not move the file — it stays in
`.planning/todos/pending/`, because piece 2 is genuinely still open and moving it to `completed/`
would misrepresent the work as finished.

Follow the precedent's structure exactly:

- A `## Partial resolution` heading carrying its attribution. **Correction (verified against both
  cited files, not assumed):** neither precedent actually demonstrates a
  `## Resolution (quick task <id>, <date>)` heading — the first uses `## Partial resolution
  (Phase 12)` (phase-attributed), and the second uses a bare `## Resolution` with no parenthetical
  at all. The `(quick task <id>, <date>)` parenthetical form is real in this repo, but only under a
  plain `## Resolution` heading on a fully-closed item:
  `.planning/todos/completed/2026-08-11-audit-dto-and-controller-test-coverage-for-validation-bindin.md:69`
  reads `## Resolution (quick task 260811-qru, 2026-08-11)`. No single existing file combines
  "Partial resolution" (this todo's own state — piece 2 stays open) with a quick-task parenthetical,
  so use `## Partial resolution (quick task 260908-mtl, 2026-09-08)` as a deliberate synthesis of
  the two established patterns — the word from the first precedent (because this is genuinely
  partial), the attribution shape from the completed-todo precedent (because a quick task, not a
  phase, is doing this work) — not as a string already found verbatim in either cited file.
- One bullet per piece of the original Solution section, each labelled with its state:
  - Piece 1, renaming — CLOSED. State the three old-to-new title mappings, and state that each
    dashboard's `description` (grafana.com ID, revision, fetch date) and `uid` were deliberately
    left untouched, so provenance and dashboard identity both survive.
  - Piece 2, public dashboard links — STILL OPEN. Restate that it needs a live Grafana session,
    a per-dashboard panel review for real hostnames / internal IPs before publishing (because the
    data genuinely becomes public, not merely link-obscured), and a decision on which of the three
    get links.
- A closing sentence, in the precedent's own voice, stating that the todo stays in `pending/`
  rather than moving to `completed/` because the remaining piece is real work, not a formality.

Leave the existing frontmatter and the `## Problem` / `## Solution` sections as they are — the
precedent files preserve the original text and append rather than rewriting history. Do not add a
`resolved:` frontmatter key; that key belongs to fully-closed todos in `completed/` (present on 32
of the 58 files there), and setting it here would make a partially-open todo look closed to any
tooling or reader that keys on it.
  </action>
  <verify>
    <automated>
test -f .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md && echo "OK: todo still in pending/"
    </automated>
    <automated>
test ! -f .planning/todos/completed/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md && echo "OK: todo was not moved to completed/"
    </automated>
    <automated>
rg -q '^##\s*(Partial resolution|Resolution)' .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md && echo "OK: resolution section added"
    </automated>
    <automated>
rg -q '260908-mtl' .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md && echo "OK: attributed to this quick task"
    </automated>
    <automated>
rg -q '^resolved:' .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md && { echo "FAIL: resolved: key set on a still-open todo"; exit 1; }; echo "OK: no resolved: key"
    </automated>
    <automated>
rg -q '^## Problem' .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md && rg -q '^## Solution' .planning/todos/pending/2026-09-07-groom-grafana-dashboards-and-expose-public-links.md && echo "OK: original sections preserved"
    </automated>
  </verify>
  <done>
    The todo remains in `pending/`, still carries its original Problem and Solution sections, and
    gains a dated section attributing the rename to quick task 260908-mtl, marking piece 1 closed
    and piece 2 still open, with an explicit statement of why it stays pending.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| public internet -> Caddy -> Grafana | Grafana is published on a public HTTPS hostname behind D-03's single authentication gate; every metric this project collects sits behind that login. |
| git repo -> VM disk (deploy) -> Grafana file provisioner | The dashboard JSON committed here is bind-mounted into the grafana container and read at runtime by the provisioner. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-260908mtl-01 | Information Disclosure | dashboard `title` fields | low | accept | The three new titles are generic capability names ("VM Host Metrics", "Per-Container Resource Usage", "Postgres Internals") containing no hostname, IP, container name or credential. They also remain behind D-03's authentication gate — this task changes no exposure. The genuine disclosure question belongs to the todo's piece 2 (public links), which is explicitly out of scope and stays pending precisely so that panel-level review is not skipped. |
| T-260908mtl-02 | Tampering | vendored dashboard JSON | low | mitigate | The change is constrained to one line per file and proven so by a `git diff --numstat` gate asserting exactly 3 files at exactly 1 insertion + 1 deletion each, plus positive greps that each `uid` and each grafana.com provenance string is still present. An unintended edit elsewhere in a 468KB vendored file fails the gate rather than passing unnoticed. |
| T-260908mtl-03 | Repudiation | grafana.com provenance in `description` | medium | mitigate | The `description` field is the only in-artifact record of which upstream dashboard, revision and fetch date each file came from, and `dashboards.yaml` explicitly directs future maintainers to it. Erasing it would make the vendored copies unattributable. Mitigated by an explicit positive grep per file for the exact provenance string, not merely by an instruction to be careful. |
| T-260908mtl-SC | Tampering | npm/pip/cargo installs | n/a | accept | No package-manager install occurs in this plan — no dependency is added, removed or upgraded. The package-legitimacy gate is not applicable. |
</threat_model>

<verification>
Run all of Task 1's and Task 2's automated gates. Beyond those:

- **Repo-wide blast-radius check** — confirm no non-historical file still refers to a renamed
  dashboard by its old title. The only permitted remaining hits are under
  `.planning/phases/12-self-hosted-observability-stack/` (RESEARCH / PLAN / SUMMARY / REVIEW), which
  are the historical record of what was vendored and must not be rewritten:

  ```
  rg -n 'Node Exporter Full|Cadvisor exporter|PostgreSQL Exporter' docs/ docker/ .github/ scripts/ | grep -v 'dashboards/json/'
  ```

  Expect no output. Any hit in `docs/` or `.github/` is a real stale reference to fix; a hit under
  `.planning/phases/` is expected and correct to leave alone.

- **Commit gate cost, expected not exceptional** — `.githooks/pre-commit` runs gitleaks on the
  staged diff, then `./gradlew spotlessCheck`, then `./gradlew fastTest`, roughly four minutes
  combined, even though this change touches only JSON and markdown and no Java. That is the
  designed behaviour. Do NOT reach for `--no-verify`; wait it out.

- **Live effect, deferred to next deploy (not a gate here)** — the file provider polls every 30
  seconds (`updateIntervalSeconds: 30` in `dashboards.yaml`), so the new titles appear in Grafana
  on its next read after the changed files reach the VM's bind mount, with no container restart and
  no Compose change. Because the provisioner keys on `uid`, this updates the three existing
  dashboards in place. No live VM verification is required for this quick task to be complete.
</verification>

<success_criteria>
- Three dashboards renamed to "VM Host Metrics", "Per-Container Resource Usage" and
  "Postgres Internals"; no old title remains in any JSON file.
- All three files still valid JSON; all three `uid` values and all three grafana.com provenance
  strings byte-identical to before.
- Total JSON diff: 3 files, 1 insertion and 1 deletion each.
- The todo stays in `.planning/todos/pending/`, records piece 1 as closed by quick task 260908-mtl,
  and leaves piece 2 (public dashboard links) open in the shape this repo already uses for
  partially-resolved todos.
- No `docs/`, `.github/`, `scripts/` or `docker/` file still names a dashboard by its old title;
  `.planning/phases/12-*` historical artifacts left untouched.
</success_criteria>

<output>
Create `.planning/quick/260908-mtl-groom-the-three-phase-12-grafana-dashboa/260908-mtl-SUMMARY.md` when done
</output>

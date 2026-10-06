---
phase: quick-261005-sun
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docs/diagrams/mermaid-config.json
  - docs/diagrams/render-manifest.tsv
  - docs/diagrams/process/activity-pipeline.mmd
  - docs/diagrams/process/activity-pipeline.png
  - docs/diagrams/physical/production-host.mmd
  - docs/diagrams/physical/production-host.png
  - docs/diagrams/scenarios/signin.mmd
  - docs/diagrams/scenarios/signin.png
  - docs/diagrams/scenarios/signup.mmd
  - docs/diagrams/scenarios/signup.png
  - docs/diagrams/scenarios/error-status-split.mmd
  - docs/diagrams/scenarios/error-status-split.png
  - docs/diagrams/scenarios/push-to-deploy.mmd
  - docs/diagrams/scenarios/push-to-deploy.png
  - docs/diagrams/scenarios/inbound-packet-path.mmd
  - docs/diagrams/scenarios/inbound-packet-path.png
  - docs/diagrams/architecture-activity-feed-read.mmd
  - docs/diagrams/architecture-activity-feed-read.png
  - docs/diagrams/architecture-mutation-sequence.mmd
  - docs/diagrams/architecture-mutation-sequence.png
  - docs/diagrams/architecture-signin-scenario.mmd
  - docs/diagrams/architecture-signin-scenario.png
  - scripts/render-diagrams.sh
  - scripts/verify-diagrams.py
  - scripts/verify-diagrams-selftest.py
  - README.md
  - docs/ARCHITECTURE.md
  - docs/AUTH_FLOWS.md
  - docs/INFRA_ARCHITECTURE.md
  - docs/DIAGRAM_CONVENTIONS.md
  - docs/learning/00-README.md
  - docs/learning/01-domain-model-and-schema.md
  - docs/learning/02-persistence-and-queries.md
  - docs/learning/03-optimistic-locking.md
  - docs/learning/04-service-layer-and-access-control.md
  - docs/learning/05-api-layer.md
  - docs/learning/06-security-and-sessions.md
  - docs/learning/07-events-and-activity-feed.md
  - docs/learning/08-testing-strategy.md
  - docs/learning/09-build-quality-and-ci.md
  - docs/learning/10-infrastructure-and-deployment.md
  - docs/learning/11-observability.md
  - .planning/todos/pending/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md
  - .planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md
  - .planning/todos/pending/2026-10-05-learning-guide-describes-the-compose-era-deployment.md
autonomous: true
requirements: [261005-sun]

estimate:
  tokens: 1150000
  raw_tokens: 1150000
  tasks: 5
  confidence: low

must_haves:
  truths:
    - "Every file under docs/diagrams/ except render-manifest.tsv and mermaid-config.json sits directly in one Kruchten view folder (process/, physical/, scenarios/ today), named <subject>.mmd with a <subject>.png twin, kebab-case, with no area prefix and no diagram-type suffix (D1)"
    - "No surviving diagram, standalone or inline in README.md and docs/learning/, traces class-to-class or controller-to-service calls; every dropped diagram's protocol-level facts are still stated in the prose that embedded it (D2, D5)"
    - "Every standalone PNG is rendered by the pinned renderer at scale 2 with useMaxWidth off, has natural width <= 1117 CSS px and displayed height <= 1600 CSS px at the measured 838 px GitHub column, and is embedded as <img width=natural width>; `bash scripts/render-diagrams.sh --check --all` exits 0 (D3)"
    - "Every node, edge and label of every surviving diagram (7 standalone + 6 inline) has a source citation or an explicit 'could not verify' row in the SUMMARY verification tables, and stale claims are fixed in the source (D4)"
    - "Every surviving flowchart, standalone or inline, uses the TB direction keyword and opens with the shared init line (D5)"
    - "`python3 scripts/verify-diagrams.py check` exits 0 on the final tree, exits 1 on a planted out-of-folder diagram, a missing twin and a dangling doc reference, and `python3 scripts/verify-diagrams-selftest.py` passes (D6, D7)"
    - "No authoritative doc (README.md, docs/ outside wiki/ and raw/) names a legacy diagram path, and the wiki lint summary is byte-identical to the planning-time baseline"
  artifacts:
    - path: docs/diagrams/mermaid-config.json
      provides: "render-time config: flowchart.useMaxWidth=false, sequence.useMaxWidth=false (the squeeze fix)"
    - path: scripts/render-diagrams.sh
      provides: "view/subject names, -c mermaid-config.json, --check still compares against the pinned renderer"
    - path: scripts/verify-diagrams.py
      provides: "check [--diagram view/subject ...] and report; stdlib-only inventory, reference, legibility, flowchart and class-label gate"
    - path: scripts/verify-diagrams-selftest.py
      provides: "proof every rule fires on a planted violation and stays silent on its lookalike"
    - path: docs/DIAGRAM_CONVENTIONS.md
      provides: "keep test, view-to-folder map, naming scheme, legibility test, embed format, inventory check"
  key_links:
    - from: scripts/render-diagrams.sh
      to: docs/diagrams/mermaid-config.json
      via: "-c /data/mermaid-config.json on every docker run, render and check mode"
    - from: scripts/verify-diagrams.py
      to: docs/diagrams/render-manifest.tsv
      via: "scale column turns PNG IHDR width into natural width; uniform-scale rule pins it to 2"
    - from: scripts/verify-diagrams.py
      to: README.md, docs/*.md, docs/learning/*.md
      via: "every <img src=...diagrams/...png width=N> must have N == natural width; every diagrams/ path must resolve"
    - from: docs/DIAGRAM_CONVENTIONS.md
      to: scripts/verify-diagrams.py
      via: "the conventions doc names the guard as the mechanical inventory check and states its constants"
    - from: docs/INFRA_ARCHITECTURE.md
      to: docs/diagrams/physical/, docs/diagrams/scenarios/
      via: "Maintenance Note file list names the new paths and the guard"
---

# Quick task 261005-sun: groom every diagram into Kruchten 4+1 view folders, legible and verified

<objective>
Restructure `docs/diagrams/` into one folder per Kruchten view with one naming scheme, delete
diagrams that trace classes, fix the rendering bug that makes most PNGs unreadable, re-verify each
surviving diagram (standalone and inline) against the live code and config, and add a local guard
that keeps the layout, the links and the legibility from regressing.

Purpose: the diagrams were meant to follow 4+1 from the start, but 10 standalone files sit flat
under three area prefixes. Measured during planning, all 10 render text at 3.4 to 4.9 CSS px on
GitHub, except two which are oversized or too tall (table below). Several trace controller → service
→ repository calls that change with every refactor. 15 of the 22 inline blocks fail the keep test.

Output: 7 standalone diagrams in `process/`, `physical/`, `scenarios/` (8 or 9 if the width gate
forces a split), 6 inline blocks kept, `scripts/verify-diagrams.py` with its selftest, a rewritten
`docs/DIAGRAM_CONVENTIONS.md`, and every embed and link updated.

Data flow (3 sentences, per the project directive): `scripts/render-diagrams.sh` feeds each
`<view>/<subject>.mmd` and `mermaid-config.json` to the digest-pinned mermaid-cli container. It
writes a PNG at 2x the diagram's natural layout width. `scripts/verify-diagrams.py` reads that PNG's
IHDR and the manifest scale to recover the natural width, then checks the width against the
legibility limits and against the `width=` attribute of every doc that embeds it.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.claude/CLAUDE.md
@docs/SESSION_LESSONS.md
@docs/DIAGRAM_CONVENTIONS.md
@docs/CODE_STYLE.md
@scripts/render-diagrams.sh
@docs/diagrams/render-manifest.tsv
@scripts/verify-comments-selftest.py
@.planning/todos/pending/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md
@.planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md
</context>

## Measured at planning time (2026-10-05)

**Root cause of the unreadable PNGs.** mmdc renders into an 800 px viewport, and Mermaid's default
`useMaxWidth: true` shrinks every SVG to fit inside it (784 px) before rasterising. `-s` multiplies
pixels, not layout width: 784 × 1, × 2 and × 4 are exactly the committed widths of 784, 1568 and
3136. Natural widths below come from the SVG viewBox of probe renders made with the pinned image
(`@sha256:a6fb0574…`). The display column `D` was measured with headless Chromium on the public repo.

| Diagram (current name) | Natural W×H | Committed PNG | Text px in PNG | Text px shown on GitHub (D=838) | Shown height |
|---|---|---|---|---|---|
| architecture-activity-feed-read | 2760×740 | 3136×896 | 18.2 | 4.9 | 225 |
| architecture-error-response-split | 3602×1832 | 3136×1756 | 13.9 | 3.7 | 426 |
| architecture-mutation-flowchart | 511×1283 | 2044×5060 | 64.0 | 26.2 (oversized) | 2104 |
| architecture-mutation-sequence | 3908×1258 | 3136×1100 | 12.8 | 3.4 | 270 |
| architecture-signin-scenario | 3416×2078 | 3136×2112 | 14.7 | 3.9 | 510 |
| auth-signin-scenario | 2839×2766 | 784×862 | 4.4 (no pixels to zoom into) | 4.4 | 764 |
| auth-signup-scenario | 3032×2604 | 784×739 | 4.1 (no pixels to zoom into) | 4.1 | 673 |
| infra-delivery-scenario | 3450×1930 | 1568×878 | 7.3 | 3.9 | 469 |
| infra-packet-path-scenario | 1153×2491 | 1568×3388 | 21.8 | 11.6 | 1810 |
| infra-physical-deployment | 3199×2153 | 1568×1056 | 7.8 | 4.2 | 564 |

Mermaid 11.17's generated CSS uses 16 px for every label, in both flowcharts and sequence diagrams.
The only other font sizes are 12 px for tooltips and 18 px for titles, and no diagram here uses
either.

**GitHub's markdown column width (`article.markdown-body` clientWidth, logged out, 2026-10-05):**

| Viewport | Blob view of docs/*.md | Repo-home README |
|---|---|---|
| 1024 | 605 | 582 |
| 1280 | 861 | 838 |
| 1366 | 947 | 838 |
| 1440 / 1920 | 1012 | 838 |

`D = 838`: the narrowest column at a viewport of 1280 px or wider. Viewports narrower than 1280
are out of target. GitHub keeps the `width` attribute on `<img>` and adds `max-width: 100%`
(confirmed via `gh api /markdown`).

**Other facts:** no `docs/wiki/**` diagram link resolves today, because `docs/wiki/*/diagrams/` and
`docs/wiki/diagrams/` do not exist. The wiki lint baseline is `0 fidelity suspect(s), 20 evidence
error(s), 51 unreferenced raw file(s)`. The pre-commit hook runs gitleaks, then
`verify-comments.py check`, then `spotlessCheck`, then `fastTest`, so every commit takes minutes.
Learning chapters 00, 05, 06, 08, 09, 10 and 11 still describe the Compose-era deployment: Caddy
mentions are 15/1/36/7/31/65/28, and `deploy-to-netcup` appears in 09, 10 and 07. `Caddyfile`,
`docker/` and `docker-compose.prod.yml` no longer exist.

## Target layout and naming (D1)

`docs/diagrams/<view>/<subject>.mmd` plus `docs/diagrams/<view>/<subject>.png`. The manifest key is
`<view>/<subject>`. The view is one of `logical`, `process`, `development`, `physical`, `scenarios`
(Kruchten's own names; Scenarios is the "+1"). A folder exists only while it holds a diagram, so
today that is `process/`, `physical/` and `scenarios/`. `<subject>` matches
`[a-z0-9]+(-[a-z0-9]+)*` and names what is drawn. It never names the view (the folder carries that),
never the diagram type (flowchart or sequence is a rendering choice that can change while the
subject stays the same), and never the old area prefix. Only two files sit at the top level:
`render-manifest.tsv` and `mermaid-config.json`.

| Final path | From | View rationale |
|---|---|---|
| `process/activity-pipeline` | architecture-mutation-flowchart | threads, async executor, broker, consumer: process boundaries |
| `physical/production-host` | infra-physical-deployment | nodes and hardware. If the width gate forces a split, the outside-the-VM nodes go to `physical/delivery-nodes` |
| `scenarios/signin` | auth-signin-scenario | HTTP/session contract of one route |
| `scenarios/signup` | auth-signup-scenario | HTTP/session contract of one route |
| `scenarios/error-status-split` | architecture-error-response-split | which layer answers 401/403/400/409 |
| `scenarios/push-to-deploy` | infra-delivery-scenario | one end-to-end path across physical nodes. Split fallback: `push-to-image` + `image-to-rollout` |
| `scenarios/inbound-packet-path` | infra-packet-path-scenario | one packet's path across network layers |

## Keep/drop/move verdicts (D2, D5)

The test: does the diagram's truth survive a refactor? The categories that pass are runtime
topology, process/thread/messaging boundaries, deployment, protocol-level scenarios (HTTP, session,
Kafka, status-code semantics), and module-level dependency direction. A diagram fails if its
lifelines or nodes are classes calling classes.

Standalone (10):

| Current | Verdict | Reason |
|---|---|---|
| architecture-activity-feed-read | DROP | Controller→Service→Verifier→Repository→Mapper call chain. Its one protocol fact (server-imposed sort `created_at desc, id desc`, giving a total order) becomes one prose sentence |
| architecture-error-response-split | MOVE+REWRITE → scenarios/error-status-split | status-code semantics and the filter-chain/MVC boundary. Lifelines become layers, not classes |
| architecture-mutation-flowchart | MOVE+REWRITE → process/activity-pipeline | thread and broker boundaries. Labels become threads, topics and tables. TD alias becomes TB |
| architecture-mutation-sequence | DROP | TaskMoveController→TaskService→Repository chain. Its facts ("200 before the Kafka send", 409 before publish) are carried by process/activity-pipeline and scenarios/error-status-split |
| architecture-signin-scenario | DROP | Duplicates auth-signin-scenario with class lifelines. ARCHITECTURE.md embeds scenarios/signin instead |
| auth-signin-scenario | MOVE+REWRITE → scenarios/signin | 400/401×3/200, cookie rotation, session commit timing. Lifelines become client/app/tables |
| auth-signup-scenario | MOVE+REWRITE → scenarios/signup | 400/409 (two codes)/201+Location/401 rollback |
| infra-delivery-scenario | MOVE+VERIFY → scenarios/push-to-deploy | GitOps delivery path across nodes. Re-lay out for width |
| infra-packet-path-scenario | MOVE+VERIFY → scenarios/inbound-packet-path | network-layer path. Re-lay out for width and height |
| infra-physical-deployment | MOVE+VERIFY → physical/production-host | deployment view. Re-lay out, or split if the width gate requires it |

Inline (22):

| Block | Verdict | Reason |
|---|---|---|
| README.md:125 physical copy | DROP, replace with `<img>` of physical/production-host | hand-maintained duplicate that already drifted (fewer nodes); one source of truth |
| learning/00:66 system overview (LR) | KEEP+FIX | runtime topology. Stale: Caddy→Traefik, Promtail→Alloy. Make it TB with the init line |
| learning/01:64 erDiagram | KEEP+VERIFY | logical view of the schema; changes only via Flyway |
| learning/01:852 timeline | DROP → 3-item dated list | not an architecture view. A list holds the same dates |
| learning/02:348 cascade-delete chain | DROP | UserService→BoardService→… method chain. Check the prose carries the 4-step bulk order |
| learning/03:331 two-client 409 | KEEP+RELABEL | protocol scenario; the `TaskService` lifeline becomes "API (version check)" |
| learning/04:67 service graph | DROP | controller→service graph. Prose keeps "no service calls upward" and cites what the ArchUnit test actually enforces |
| learning/04:247 ownership chain | DROP | OwnershipVerifier internals. The numbered list above it carries the facts |
| learning/04:690 move algorithm | DROP | restates steps 1-7 directly above it |
| learning/04:891 cascade delete | DROP | duplicate of 02:348, class chain |
| learning/05:732 two error producers | KEEP+RELABEL | layer-level status semantics; class-named lifelines become roles |
| learning/05:956 springdoc two-phase | DROP | pipeline of two project customizer classes. Check the prose states the phase-1/phase-2 order |
| learning/06:233 signin sequence | DROP → link to scenarios/signin | class lifelines, duplicate |
| learning/06:942 Caddy rate limit | DROP → pointer | depicts a removed edge (Caddy). The current limiter is a Traefik middleware with different numbers. Pointer to scenarios/inbound-packet-path |
| learning/07:74 activity pipeline | KEEP+RELABEL+VERIFY | messaging boundaries with config numbers. Lifelines named by thread/role; recorder merged into the consumer thread |
| learning/08:74 classDiagram | DROP | test base-class hierarchy. Check the prose carries the tiers |
| learning/09:427 pre-commit gates | KEEP+FIX | commit-path scenario. Stale: the comment-policy gate is missing. TB with the init line |
| learning/09:649 CI job graph | DROP → pointer | deploy-to-netcup/caddy jobs no longer exist. Pointer to scenarios/push-to-deploy |
| learning/10:207 Compose networks | DROP → pointer | decommissioned topology. Pointer to physical/production-host |
| learning/10:790 Compose deploy sequence | DROP → pointer | decommissioned path. Pointer to scenarios/push-to-deploy |
| learning/11:54 Compose observability | DROP → pointer | decommissioned (promtail, cadvisor, docker.sock). Pointer to physical/production-host |
| learning/kafka-architecture-overview.md:10 | UNTOUCHED | untracked user WIP. Inventoried only; the guard scans tracked files only |

Totals: standalone 3 drop, 7 kept. Inline 15 drop, 6 kept, 1 untouched. "Pointer" means: replace the
block with one sentence saying the section describes the Docker Compose deployment that the k3s
cutover replaced on 2026-09-26, linking to the current diagram's PNG. The chapter rewrite becomes a
new todo, because it is a separate seam (PATTERN_SELECTION step 4).

## Legibility spec (D3)

- Render: pinned image, `-c docs/diagrams/mermaid-config.json` (`flowchart.useMaxWidth=false`,
  `sequence.useMaxWidth=false`), scale 2 for every row. The PNG is then 2 × natural width, and a
  label is 32 device px in the raster, so it stays sharp on devicePixelRatio-2 screens and on click-through.
- Natural width `W = round(PNG width / 2)`, natural height `H = round(PNG height / 2)`.
- Displayed text px = `16 × min(1, 838 / W)`. Floor: 12 CSS px, which is ¾ of the 16 px body
  text. This is a chosen bound, not a standard. Hence `W_MAX = floor(16 × 838 / 12) = 1117`.
- Displayed height `H × min(1, 838 / W) ≤ H_MAX = 1600` CSS px (about two 800 px screens; also a
  chosen bound).
- Embed: `<img src="<relative path>.png" width="<W>" alt="<Kind>: <what it shows>">`. That is the
  standard idiom for a 2x raster at an explicit CSS width. Markdown image syntax is not allowed for a
  diagrams PNG, because it would display a 2x raster at up to twice its intended size. Plain
  `[PNG](…)` links are fine.
- Levers when a diagram fails, in this order: (1) shorten labels and notes, moving rationale into
  the embedding doc's prose, with notes at most 2 short lines; (2) re-lay out (fewer lifelines by
  merging same-thread or same-node roles, rows of subgraphs aligned with `~~~`, inner subgraph
  `direction LR` while the top level stays TB); (3) split into two subjects in the same view. Never
  raise a threshold to pass. If a diagram still fails after the three levers, report it with
  numbers as a "could not" result.
- Proof per diagram (SUMMARY legibility table): `python3 scripts/verify-diagrams.py report` row
  (PNG W×H, W, displayed text px, displayed height), plus a visual check. The visual check: downscale
  the PNG to `min(W, 838)` with Pillow (12.3.0, present; ad hoc only, never imported by committed
  code) into a `mktemp` file, view it with the Read tool, then delete it.

## Rewrite rules for kept diagrams (D2, D4)

Lifelines and nodes are named by runtime role: client, filter chain, MVC dispatch, a thread pool, a
table, a topic, a pod, a node. Class names stay in the embedding doc's prose. Messages carry
protocol facts: method + path, status + ProblemDetail `code`, cookie or header, SQL-level action,
topic, consumer group. Each long note's content moves to the embedding doc's prose if that prose
does not already state it, so nothing is lost; the SUMMARY lists each moved fact. Every flowchart's
first line is the shared init line, copied byte-for-byte from today's `infra-physical-deployment.mmd`
line 1, followed by the TB keyword, one vertical spine with outcomes as leaves, and `~~~` only for
rank alignment (the conventions doc's rules 1-4).

Verification table (SUMMARY, one per diagram): `| # | element (node/edge/label/note) | claim |
source (path:line or symbol) | confirmed / fixed (old → new) / could not verify (why) |`. Tools:
serena `find_symbol`/`find_referencing_symbols`/`get_symbols_overview` for Java, scoped `rg` for
config, and Context7 for framework semantics (for example `FixedBackOff`'s attempt count). No SSH to
the production VM is part of this plan. A fact that only the live VM holds is cited to recorded
evidence with its date, or marked could-not-verify.

## Approaches considered (project directive: 2 alternates per decision)

Decision A: layout and naming

| Approach | Pros / Cons | Why picked |
|---|---|---|
| A1. Folder per view, `<view>/<subject>.{mmd,png}` | + `ls docs/diagrams/scenarios` is the view inventory, and a misplaced file is a path fact a script can check. + Names stay short and stable when a diagram's type changes. − One rename of every path and embed now. − Manifest keys gain a `/`, so the render script needs subdir support in check mode | **Picked.** Fixed by the user (D1). The view is a placement fact, so the path should carry it |
| A2. Flat dir, view as filename prefix (`scenario-signin.mmd`) | + No script change. − Prefix vocabulary drift is exactly how today's `architecture-/auth-/infra-` mix happened, and nothing structural stops a fourth prefix. − 7+ files in one listing stops reading as views | Rejected: repeats the current failure mode |
| A3. Co-locate each diagram with its embedding doc, view recorded in the manifest | + Doc and diagram move together. − A diagram embedded by two docs (signin: ARCHITECTURE + AUTH_FLOWS) has no single home. − The render script must walk docs/. − The view becomes metadata a reader never sees | Rejected: shared diagrams break the model |

Decision B: resolution fix

| Approach | Pros / Cons | Why picked |
|---|---|---|
| B1. PNG at scale 2 with useMaxWidth off, `<img width=W>`, width/height gate, re-layout/split | + Fixes the measured root cause (the 784 px squeeze). + Pixel-pinned output keeps `--check` meaningful, and `--check` now catches label edits too, because width tracks content. + Sharp at 2x. − The doc's `width=` couples to the render, so the guard has to enforce it. − Wide diagrams must be redrawn, which is real work | **Picked** |
| B2. SVG output | + Infinitely sharp, text-based diff. − Text uses the viewer's fonts, but box sizes were computed with the container's fonts, so labels can clip in `foreignObject` on any machine without "trebuchet ms". − Generated ids make every re-render a large noisy diff. − The IHDR drift check would need replacing. − Does not fix inline legibility: a 3602-wide SVG at 838 is still 3.7 px text | Rejected: trades a pinned renderer for host-dependent text, and does not fix the root cause |
| B3. GitHub-native inline ```mermaid for everything | + Pan/zoom viewer, no binaries. − Renderer version is GitHub's and unpinned, so the source/render drift check is lost. − D1 requires `.mmd` plus a rendered artifact. − Forces mass extraction in reverse | Rejected: contradicts D1 and loses reproducibility |
| (B0. Raise `-s` only) | Measured: scale does not change layout width, so text stays at 784 / W of its size | Rejected by measurement |

## Non-obvious trade-offs

- **History vs. rewrite.** Git detects a rename only above 50% similarity, and a rewritten `.mmd`
  falls below that. Every move is therefore a rename-only commit (byte-identical files plus path
  edits in docs/manifest), and content rewrites land in later commits, so `git log --follow` keeps
  working.
- **Guard reads only the index.** It enumerates files with `git ls-files` and never walks the
  filesystem from the repo root. A walk would open `.env`/`.env.prod`, and gitignored files are
  never indexed. The cost: a new file is invisible to the guard until `git add`. A side benefit:
  the user's untracked `docs/learning/kafka-architecture-overview.md` is out of scope automatically.
- **The legibility rule trusts the renderer.** A PNG produced under the old squeeze would pass a
  width check computed from IHDR. `render-diagrams.sh --check --all` under the new config is the
  proof the PNG is unsqueezed, so both must be green at the end.
- **State window.** From T1's config commit until T4, `--check` fails for the not-yet-re-rendered
  diagrams. Seven of them already fail today, per the drift todo. This is expected and recorded,
  not fixed early.
- **Repo growth.** Each re-render adds roughly 100-400 KB of PNG to history, the same order as
  today. Gated widths cap any PNG at about 2234 px wide.
- **Not in CI.** `invariant-checks.yml` ignores `docs/**`, and wiring the guard in would cost a full
  deploy pipeline per docs commit (the render script's header records why). Local-only, like
  `render-diagrams.sh`.
- **Wiki copies.** `docs/wiki/**` links already fail to resolve. Per CLAUDE.md the originals are
  edited and the copies are not. The guard excludes `docs/wiki/` and `docs/raw/`, and the SUMMARY
  reports each wiki reference to a renamed or dropped diagram.
- **Dropped information.** Every DROP has a carry-over check, and the SUMMARY lists each fact as
  carried or as intentionally dropped class-level detail.

## Source coverage audit

| Source item | Covered by |
|---|---|
| GOAL: Kruchten structure, positioning | T1 (process/), T2 (all moves) |
| GOAL: trim fat / drop class-level | T2 (standalone drops), T5 (inline drops) |
| GOAL: standard naming | T1, T2; guard `bad-name` |
| GOAL: fix resolution | T1 (config, scale), T3, T4 (re-layout); guard legibility rules |
| GOAL: update and verify survivors | T1, T3, T4, T5 verification tables |
| D1 layout/naming scheme | T1, T2 |
| D2 keep test + verdict table | this plan's verdict tables; T2, T5 execute them |
| D3 measured legibility | Legibility spec; T1, T3, T4 |
| D4 verification per diagram | Rewrite rules; method named per task |
| D5 inline blocks, tiered | T5 (own commits) |
| D6 conventions, render paths, links | T2 (conventions), T1 (render), T2/T4 (links) |
| D7 regression guard, fail then pass | T1 (guard + selftest, fails on the pre-migration tree), T5 (planted break/restore) |

No research doc or REQUIREMENTS IDs exist for a quick task. Nothing is deferred.

<tasks>

<task type="tracer" tdd="true">
  <name>Task 1 (tracer): process/activity-pipeline end to end: view folder, natural-width render, width-pinned embed, inventory guard</name>
  <files>docs/diagrams/architecture-mutation-flowchart.mmd → docs/diagrams/process/activity-pipeline.mmd, docs/diagrams/architecture-mutation-flowchart.png → docs/diagrams/process/activity-pipeline.png, docs/diagrams/mermaid-config.json, docs/diagrams/render-manifest.tsv, scripts/render-diagrams.sh, scripts/verify-diagrams.py, scripts/verify-diagrams-selftest.py, docs/ARCHITECTURE.md, README.md, .planning/todos/pending/2026-09-05-convert-architecture-mutation-flowchart-td-to-tb.md → .planning/todos/completed/</files>
  <behavior>
    Selftest (in-memory fixtures, modelled on scripts/verify-comments-selftest.py). Each rule fires on its planted case and stays silent on its lookalike:
    - outside-view-folder: a .mmd directly under docs/diagrams/, and one under docs/diagrams/misc/. Lookalike: render-manifest.tsv, mermaid-config.json
    - missing-twin: .mmd without .png and .png without .mmd
    - manifest-mismatch: a diagram on disk with no manifest row, and a row with no .mmd
    - uniform-scale: a manifest row with scale 4
    - bad-name: stems with an old area prefix, a type suffix (-scenario, -flowchart, -sequence, -diagram), or not kebab-case
    - dangling-reference: a link, img src or code-span path containing diagrams/ that does not resolve. Lookalikes: a glob token with *, a reference inside docs/wiki/ (not scanned)
    - legibility-width: PNG 2400 px wide at scale 2. legibility-height: displayed height over 1600
    - embed-width: markdown image syntax for a diagrams PNG, and an img width off by more than 1. Lookalike: a plain [PNG](…) link
    - flowchart-rules: a standalone .mmd or inline mermaid fence with a non-TB keyword, or a missing or altered init line. Lookalikes: sequenceDiagram and erDiagram blocks, and an inner `direction LR` subgraph
    - class-level-label: a CamelCase token ending Controller/Service/Repository/Mapper/Publisher/Consumer/Recorder/Provider/Handler/Resolver/Verifier/Filter in a diagram source or inline fence. Lookalikes: "@ControllerAdvice", "kustomize-controller", "Error mapper"
    - zero diagrams or zero docs scanned exits 2. A clean fixture exits 0
    - --diagram view/subject restricts diagram-scoped rules to that diagram and the references to it
  </behavior>
  <action>
    Read docs/SESSION_LESSONS.md first (pre-commit takes minutes per commit, so give `git commit` a generous timeout, keep stdin redirected, never pass --no-verify). Read docs/CODE_STYLE.md rule 14 (comments in .sh, .py and .tsv are linted by scripts/verify-comments.py: no planning ids such as quick-task or decision ids, a summary of at most 2 lines, long narration behind `Decisions:`/`Known holes:`). Stage by explicit path only. Never stage `.serena/` or `docs/learning/kafka-architecture-overview.md` (untracked user files).

    Step 1, rename-only commit (per D1 and the history trade-off). Create docs/diagrams/process/, then `git mv` the mutation flowchart .mmd and .png to process/activity-pipeline.{mmd,png} with no byte change. In docs/diagrams/render-manifest.tsv, rename the row key to `process/activity-pipeline`. In scripts/render-diagrams.sh, accept names of the form view/subject: in check mode, `mkdir -p` the scratch subdirectory before `docker run`, and widen compare_one's name column. Update the embed and the source-link paths in docs/ARCHITECTURE.md ("Process View — path of a mutation") and the README.md "Diagrams" table row. Commit as `docs(quick-261005-sun): move the mutation flowchart into the process view folder`.

    Step 2, guard RED then GREEN (per D7). Write scripts/verify-diagrams-selftest.py first, covering every case in `<behavior>`. Run it and watch it fail. Then write scripts/verify-diagrams.py: stdlib only, with subcommands `check [--diagram VIEW/SUBJECT ...]` and `report` (a markdown legibility table). Enumerate with `git ls-files`. Scan docs as tracked `*.md` minus the prefixes `.planning/ .claude/ .agents/ docs/raw/ docs/wiki/ .dev/` (the same set verify-comments.py excludes). Views: logical, process, development, physical, scenarios. Allowed top-level files: render-manifest.tsv and mermaid-config.json. Constants: D=838, BODY_FONT=16, FLOOR=12, W_MAX computed as floor(BODY_FONT*D/FLOOR) (not a literal), H_MAX=1600, SCALE=2. Init line: a constant copied byte-for-byte from line 1 of the physical deployment diagram. Read PNG width and height from IHDR with `struct`, as render-diagrams.sh's compare_one does. Extract references from markdown link targets, `<img src>` values and code spans whose token contains `diagrams/` and ends in .mmd/.png/.svg. Resolve each relative to the doc's directory, falling back to the repo root for tokens starting `docs/`, and skip tokens containing `*`. The embed-width rule checks every `<img>` whose src resolves to a diagrams PNG. Output one line per violation, `FAIL <rule-id> <path>[:<line>] <message>`, then a summary line. Exit 0 if clean, 1 on violations, 2 on an empty scan or bad usage. The module docstring states the summary, then `Decisions:` (measured D table date 2026-10-05; the 12 px floor and 1600 px height are chosen bounds; why the guard reads only the index; why it is not in CI) and `Known holes:` (unstaged files are invisible; the guard trusts that the PNG came from render-diagrams.sh, which `--check` proves; class-level-label is a token heuristic, so judgment stays in review; the target viewport is 1280 px or wider). Run the selftest to green. Run the full `check` on the real tree. It must exit 1 and report outside-view-folder for each of the nine root-level diagrams: that is the "fails on the broken state" evidence, so paste it into the SUMMARY. Commit as `chore(261005-sun): add diagram inventory guard and selftest`.

    Step 3, natural-width rendering (per D3). Create docs/diagrams/mermaid-config.json with only flowchart.useMaxWidth=false and sequence.useMaxWidth=false. Styling stays in each flowchart's init line, so GitHub-rendered inline blocks keep their look. Pass `-c /data/mermaid-config.json` in both docker invocations (the existing /data mount covers it, read-only in check mode). Set every manifest row's scale to 2 and rewrite the manifest header comment: scale is uniformly 2, and natural width is the PNG width divided by 2. Edit the render script's header Decisions block only where it is now false (scale-reproduces-committed-width, the squeeze): state the config and that `--check` now catches label drift through width. Keep the IHDR blind-spot note and the "not in CI" reasoning. `--check` will fail for the other nine until T3/T4 re-render them. Record that, do not fix it here.

    Step 4, rewrite docs/diagrams/process/activity-pipeline.mmd as a Process view (per D2, D4 and the rewrite rules). Line 1 is the init line, then the TB keyword (replacing the TD alias). Spine: mutating HTTP request → request thread (@Transactional mutation) → transaction commit → publisher on the kafka-publish-* executor (AFTER_COMMIT) → topic kanban.activity → consumer thread (group activity-log) → processed? → activity_log insert, idempotent on event_id. Leaves: "HTTP response returned; does not wait on Kafka" off the commit; "Schema Registry: schema-id lookup, never registers" off the publisher; "kanban.activity.dlt after N retries, 1 s apart" off the failure arm. No class names in labels. Verify each element. Use serena on KafkaEventPublisher (the @Async executor name, the @TransactionalEventListener phase), AsyncConfig.kafkaPublishExecutor (the thread-name prefix), ActivityLogConsumer (@KafkaListener topic and group), KafkaConsumerConfig (DefaultErrorHandler with FixedBackOff(1000L, 3L), the DeadLetterPublishingRecoverer target, the topic partition count) and ActivityLogRecorder (the existsByEventId path). Check application.properties for auto.register.schemas=false and spring.kafka.consumer.group-id. Confirm with Context7 whether FixedBackOff's second argument counts retries or total attempts, and label N accordingly. Re-render in place with `bash scripts/render-diagrams.sh process/activity-pipeline`. Run `python3 scripts/verify-diagrams.py report` and apply the Legibility spec. Replace the ARCHITECTURE.md markdown image with `<img src="diagrams/process/activity-pipeline.png" width="W" alt="Flowchart: …">` and keep the `<sub>` source line. Update the italic caption and the surrounding prose only where they contradict the new labels. Do the visual check. Write the verification and legibility tables into the SUMMARY.

    Step 5. `git mv` the TD-to-TB todo into .planning/todos/completed/ and append a one-line resolution. Commit as `docs(quick-261005-sun): render diagrams at natural width and redraw the activity pipeline at process level`.
  </action>
  <verify>
    <automated>T=$(mktemp) && python3 scripts/verify-diagrams-selftest.py && python3 scripts/verify-diagrams.py check --diagram process/activity-pipeline && bash scripts/render-diagrams.sh --check process/activity-pipeline && ! rg -n 'flowchart TD' docs/diagrams && python3 scripts/verify-comments.py check && H=$(git log --follow --format=%h -- docs/diagrams/process/activity-pipeline.mmd) && test "$(printf '%s\n' "$H" | wc -l)" -ge 2 && { python3 scripts/verify-diagrams.py check > "$T"; test $? -eq 1; } && test "$(grep -c '^FAIL outside-view-folder' "$T")" -ge 9; rc=$?; rm -f "$T"; exit $rc</automated>
  </verify>
  <done>Three commits land on docs/diagram-grooming. process/activity-pipeline renders unsqueezed at scale 2 with W ≤ 1117 and displayed height ≤ 1600, is embedded with a matching width, and every element is cited in the SUMMARY. The guard and selftest exist. The guard passes for this diagram and fails, as the pre-migration evidence, for the nine root-level ones. The TD todo is closed.</done>
</task>

<task type="auto">
  <name>Task 2: Move the other nine into view folders, drop the three class-level diagrams, rewrite DIAGRAM_CONVENTIONS</name>
  <files>docs/diagrams/{auth-signin-scenario,auth-signup-scenario,architecture-error-response-split,infra-delivery-scenario,infra-packet-path-scenario,infra-physical-deployment}.{mmd,png} → docs/diagrams/{scenarios,physical}/…, docs/diagrams/{architecture-activity-feed-read,architecture-mutation-sequence,architecture-signin-scenario}.{mmd,png} (deleted), docs/diagrams/render-manifest.tsv, README.md, docs/ARCHITECTURE.md, docs/AUTH_FLOWS.md, docs/INFRA_ARCHITECTURE.md, docs/learning/06-security-and-sessions.md, docs/learning/10-infrastructure-and-deployment.md, docs/DIAGRAM_CONVENTIONS.md</files>
  <action>
    Step 1, rename-only commit (per D1). Create scenarios/ and physical/. `git mv` the six kept pairs to the final paths in the "Target layout" table, with no byte change, and rename their manifest keys. Update every authoritative reference: the embeds and source links in docs/AUTH_FLOWS.md (2 diagrams), docs/INFRA_ARCHITECTURE.md (3 embeds, plus the Maintenance Note path mentions), docs/ARCHITECTURE.md (the error-split embed, and the code spans naming both auth diagrams in the "Want the client's-eye view" paragraph), docs/learning/10 (the three-row diagram table and the open-items bullet that names the delivery diagram), README.md (the inline physical copy's `<sub>` source line, the delivery-scenario link, and the "Diagrams" section). Rewrite README's Diagrams table as View | Diagram | Answers, with one row per final diagram. Its intro names ARCHITECTURE.md, AUTH_FLOWS.md and INFRA_ARCHITECTURE.md, and the delivery row says "push to main". Do not name legacy file names anywhere in authoritative prose; git history carries the renames. Commit as `docs(quick-261005-sun): move diagrams into Kruchten view folders`.

    Step 2, drop commit (per D2). `git rm` the activity-feed-read, mutation-sequence and architecture-signin pairs, and delete their manifest rows. In docs/ARCHITECTURE.md: (a) the "Scenario — signin" section now points at scenarios/signin, and the "client's-eye view" paragraph and the "Simplified:" paragraph are rewritten so they name one signin diagram, not two; (b) remove the "Sequence view of the same mutation" section, after confirming the Process View prose states that the version check (409) runs before anything is published and that the response returns before the Kafka send (add a sentence for whichever is missing); (c) replace the "reading the activity feed" diagram with prose. That prose says the service discards the caller's sort, imposes created_at desc then id desc, and that the id tiebreak makes offset pagination a total order. Keep the existing max-page-size and concurrent-insert caveats as plain prose, and cite ActivityLogService.findAllByBoardId (verify the sort with serena). In docs/learning/06, re-point the signin diagram link to scenarios/signin.mmd. For each dropped diagram, list every label and note in the SUMMARY as "carried (where)" or "dropped: class-level detail". Commit as `docs(quick-261005-sun): drop class-level diagrams, keep their protocol facts in prose`.

    Step 3, rewrite docs/DIAGRAM_CONVENTIONS.md (per D6). Keep the 4+1 intro and "why this convention exists". Update the Process View example to name today's processes (app JVM threads, Redpanda, Traefik). Add these sections: "What earns a diagram" (the keep test and the class-level drop rule, with the reason: class graphs churn on every refactor and no check can catch the drift); "Layout and naming" (the view-to-folder table, the subject rules, the manifest key, the two allowed top-level files, and the current inventory table with view, path, question answered and embedding doc); "Rendering" (the render script, mermaid-config.json and why useMaxWidth is off, uniform scale 2, `--check`); "Legibility test" (the measured D table, the formulas, the 12 px and 1600 px bounds marked as chosen, the levers in order, the `<img width>` embed format); "Inline diagrams" (allowed in docs/learning for explanation; same keep test and flowchart rules; never a copy of a standalone diagram); "Inventory check" (`python3 scripts/verify-diagrams.py check`, `report`, `--diagram`, the selftest, the rule ids, local-only and why). In the flowchart rules, keep rules 1-4, re-point rule 3's precedent to physical/production-host.mmd (or wherever the `~~~` survives after T4, rechecked in T4), and delete the "Known outstanding offender" paragraph, which T1 resolved. Leave docs/wiki/conventions/diagram-conventions.md untouched, and prove the wiki lint is unchanged. Commit as `docs(quick-261005-sun): rewrite diagram conventions for view folders and the legibility test`.
  </action>
  <verify>
    <automated>L=$(git ls-files docs/diagrams) && test -n "$L" && test -z "$(printf '%s\n' "$L" | grep -Ev '^docs/diagrams/(render-manifest\.tsv|mermaid-config\.json|(logical|process|development|physical|scenarios)/[a-z0-9]+(-[a-z0-9]+)*\.(mmd|png))$')" && ! rg -n '(architecture|auth|infra)-[a-z-]+\.(mmd|png)' README.md docs -g '!docs/wiki/**' -g '!docs/raw/**' && test -z "$(python3 scripts/verify-diagrams.py check | awk '/^FAIL/{print $2}' | sort -u | grep -vxE 'legibility-width|legibility-height|embed-width|flowchart-rules|class-level-label')" && test "$(python3 .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py docs | tail -1)" = "0 fidelity suspect(s), 20 evidence error(s), 51 unreferenced raw file(s)" && python3 scripts/verify-comments.py check && H=$(git log --follow --format=%h -- docs/diagrams/scenarios/signin.mmd) && test "$(printf '%s\n' "$H" | wc -l)" -ge 2</automated>
  </verify>
  <done>Every diagram sits in a view folder under its final name, with history following the renames. The three class-level diagrams are gone, and each of their facts is carried or explicitly dropped in the SUMMARY. No authoritative doc names a legacy path, and every diagrams/ reference resolves. DIAGRAM_CONVENTIONS describes the new layout, naming, legibility test and guard. The only remaining guard failures are legibility, embed-width, flowchart and class-label rules, which T3-T5 own.</done>
</task>

<task type="auto">
  <name>Task 3: Redraw and verify the three application scenarios at protocol level: signin, signup, error-status-split</name>
  <files>docs/diagrams/scenarios/signin.mmd, docs/diagrams/scenarios/signin.png, docs/diagrams/scenarios/signup.mmd, docs/diagrams/scenarios/signup.png, docs/diagrams/scenarios/error-status-split.mmd, docs/diagrams/scenarios/error-status-split.png, docs/AUTH_FLOWS.md, docs/ARCHITECTURE.md</files>
  <action>
    Apply the Rewrite rules and the Legibility spec to each diagram, iterating render → `report` → adjust until it passes. A render takes seconds.

    scenarios/signin (per D2, D3, D4). Lifelines: Client, App (POST /api/signin), users table, session store (spring_session tables). Arms: invalid body → 400 VALIDATION_FAILED; unknown email → a BCrypt compare against a fixed hash for timing parity → 401 BAD_CREDENTIALS; wrong password → 401 BAD_CREDENTIALS; principal already at 2 live sessions → 401 BAD_CREDENTIALS with an identical body; success → the ceiling check runs before the session-id rotation, the security context is written and committed as the response flushes, and the reply is 200 with Set-Cookie (rotated id) and a {id, email, displayName, theme} body. Verification method: serena on AuthenticationController (the signin and authenticate helpers, the equalizer compare, the blanket catch), UserAuthenticationProvider.authenticate (the minimal principal), SecurityConfiguration.sessionAuthenticationStrategy (composite order, max sessions, maxSessionsPreventsLogin), GlobalExceptionHandler and ErrorCode. Check application.properties for the cookie name, timeouts and max-age. Cite the proving tests by rg in src/test (AuthenticationTest's ConcurrentSessionCeiling and SessionFixation classes, and the BAD_CREDENTIALS assertions).

    scenarios/signup. Same lifelines (POST /api/signup). Arms: 400 VALIDATION_FAILED; email taken → 409 DUPLICATE_RESOURCE, and the losing side of a simultaneous race gets 409 DATA_INTEGRITY_VIOLATION through the users.email unique constraint (verify the constraint name in the Flyway migrations); success → INSERT, then the same session establishment as signin drawn as one step that names signin, then 201 with Set-Cookie, Location, and the same body; authentication failure after the insert → user row deleted → 401 BAD_CREDENTIALS, not 403. Verification method: serena on AuthenticationController.signup, UserService.save/deleteById, the GlobalExceptionHandler duplicate and data-integrity handlers; rg the migrations; check whether the Location target still has no GET handler (serena on UserController mappings) and draw or omit the note to match.

    scenarios/error-status-split. Lifelines: Client, Security filter chain, MVC dispatch (controller + service), Exception handler (@ControllerAdvice). Four independent `rect` requests: no session → 401 UNAUTHENTICATED from the entry point inside the filter chain, never reaching MVC; foreign resource → 403 ACCESS_DENIED, detail never names the resource; invalid body → 400 VALIDATION_FAILED with an errors map, before the handler body runs; stale version → 409 OPTIMISTIC_LOCK_CONFLICT. Verification method: serena on SecurityConfiguration (entry-point wiring, permitAll routes), ProblemDetailAuthenticationEntryPoint.commence, the GlobalExceptionHandler methods for access-denied, validation and optimistic-lock, and ErrorCode. rg src/test for a test asserting each of the four codes and cite it.

    Doc updates. Long-note facts that leave the diagrams (timing parity, why the ceiling collapses into the wrong-password 401 so it gives no validity oracle, the bounded concurrent-signin overshoot and where its Javadoc lives, commit-after-flush timing, 200 vs 201 and Location, the race's second 409 code, rollback giving 401 not 403) must each appear once in docs/AUTH_FLOWS.md prose; add only the ones missing. Replace every embed of these three diagrams (AUTH_FLOWS.md ×2, ARCHITECTURE.md ×2) with `<img … width="W">` and keep the `<sub>` source lines. Fix any surrounding sentence that describes a lifeline or note that no longer exists. Write the three verification tables, the legibility rows and the visual-check results into the SUMMARY. Commit as `docs(quick-261005-sun): redraw auth and error scenarios at protocol level`.
  </action>
  <verify>
    <automated>python3 scripts/verify-diagrams.py check --diagram scenarios/signin --diagram scenarios/signup --diagram scenarios/error-status-split && bash scripts/render-diagrams.sh --check scenarios/signin && bash scripts/render-diagrams.sh --check scenarios/signup && bash scripts/render-diagrams.sh --check scenarios/error-status-split && python3 scripts/verify-comments.py check</automated>
  </verify>
  <done>All three render unsqueezed with W ≤ 1117 and displayed height ≤ 1600, carry no class-name labels, have matching embed widths, and pass `--check`. Each element has a citation or an explicit could-not-verify row. No fact from a removed note is lost from AUTH_FLOWS.md or ARCHITECTURE.md.</done>
</task>

<task type="auto">
  <name>Task 4: Re-lay out and verify the three infra diagrams; README embeds the canonical physical view</name>
  <files>docs/diagrams/physical/production-host.mmd, docs/diagrams/physical/production-host.png, docs/diagrams/scenarios/push-to-deploy.mmd, docs/diagrams/scenarios/push-to-deploy.png, docs/diagrams/scenarios/inbound-packet-path.mmd, docs/diagrams/scenarios/inbound-packet-path.png, docs/diagrams/render-manifest.tsv, docs/INFRA_ARCHITECTURE.md, README.md, docs/learning/10-infrastructure-and-deployment.md, docs/DIAGRAM_CONVENTIONS.md</files>
  <action>
    Apply the Rewrite rules and the Legibility spec. These three start at natural widths of 3199, 3450 and 1153, so expect lever 2 or 3. If a split is needed, use the fallback names in the Target layout table: add manifest rows, render both halves, and update the embeds and the conventions inventory table.

    physical/production-host (per D2, D3, D4). Keep a platform/CPU annotation on every node (VM x86_64, GitHub Actions runner x86_64), per the Physical View rule. Width plan: arrange the namespaces in rows aligned with `~~~` (for example kube-system/cert-manager/flux-system, then kanban-prod/kanban-nonprod/kanban-data, then monitoring), shorten labels, and move port and rationale text into INFRA_ARCHITECTURE prose. If it is still over width, split off the outside-the-VM nodes (GitHub Actions, Docker Hub, GitHub, and Flux's edges to them) into physical/delivery-nodes. Verification method: k8s/platform/namespaces.yaml (namespace list), k8s/platform/traefik/helmchartconfig.yaml (ServiceLB, externalTrafficPolicy, ports), k8s/platform/edge/clusterissuers.yaml (HTTP-01), k8s/base/app and k8s/base/redpanda (ports), k8s/data/postgres (StatefulSet image, PVC, NetworkPolicy), k8s/monitoring/controllers (which HelmReleases, DaemonSets and exporters exist), k8s/flux-system/gotk-components.yaml (controller Deployments: 6 counted at planning, recount), infra/vm/k3s/install.sh (K3S_VERSION), docs/INFRA_RUNBOOK.md (VPS product, arch, location), infra/vm/k3s-host-firewall.sh (the KANBAN-INGRESS boundary). Keep the numbered trust-boundary markers consistent with the INFRA_ARCHITECTURE and learning/10 prose that cites them (rg for the bracketed numbers in both files and fix whichever side is stale).

    scenarios/push-to-deploy. Merge lifelines by node: Developer, GitHub Actions runner (x86_64), Docker Hub, GitHub main, Flux (flux-system: image automation + kustomize-controller), Postgres (for the Flyway pre-verify hop, which today wrongly targets the app Pod lifeline), app Pod. If it is over width after levers 1-2, split into scenarios/push-to-image and scenarios/image-to-rollout. Verification method: .github/workflows/deploy.yml (trigger branch, paths-ignore, jobs and `needs:`, tag scheme, build platform, which repos are pushed, flyway-verify host-key pin and target); k8s/flux-system/image-automation.yaml (ImageRepository interval, ImagePolicy tag pattern and ordering, ImageUpdateAutomation author, interval, strategy and update path); k8s/flux-system/gotk-sync.yaml (GitRepository interval, SSH); apps-prod.yaml and apps-nonprod.yaml (separate Kustomizations); k8s/base/app (Deployment strategy, register-schemas initContainer, probe paths); the overlay kustomizations' image-policy setter markers.

    scenarios/inbound-packet-path. Bring it to W ≤ 1117 and displayed height ≤ 1600: move iptables rule text into prose, and either collapse the post-VM chain (svclb → Traefik → middleware → Service → Pod) into fewer nodes or put it in an inner `direction LR` subgraph, while keeping one TB spine. Verification method: infra/vm/k3s-host-firewall.sh (chain name, insertion point, rules); k8s/platform/traefik/helmchartconfig.yaml (ETP, hostPorts); k8s/overlays/prod/ingressroute.yaml and k8s/monitoring/configs/ingressroute.yaml (middleware names and limits). Chain names generated at runtime by k3s/CNI (the nat PREROUTING jumps, the hostport DNAT chain, the NodePorts chain) and the filter INPUT policy are not in the repo. Cite recorded evidence by rg in .planning/phases/13-* and docs/history with its date, or mark them could-not-verify.

    Docs. Replace the three INFRA_ARCHITECTURE embeds with `<img … width="W">`. In README.md, delete the inline physical copy and its "simplified rendering" caption, embed docs/diagrams/physical/production-host.png the same way, and keep the README prose paragraph after it consistent with the diagram. Update INFRA_ARCHITECTURE's Maintenance Note: the README-copy paragraph now says the README embeds the canonical PNG, so nothing is left to sync; the diagrams bullet names the view folders, mermaid-config.json, `render-diagrams.sh --check --all` and `verify-diagrams.py check`. In learning/10, re-point the diagram table if a split happened, fix the marker-range sentence, and fix the open-items bullet: drop the diagram half of the "push to master" claim if the diagram says main, and keep the INFRA_ARCHITECTURE half only if rg still finds "master" there. Update DIAGRAM_CONVENTIONS' inventory table and rule 3's `~~~` precedent to the final file. Write the three verification tables, the legibility rows and the visual checks into the SUMMARY. Commit as `docs(quick-261005-sun): re-lay out and verify the infra diagrams`.
  </action>
  <verify>
    <automated>python3 scripts/verify-diagrams.py check $(awk -F'\t' '!/^#/ && NF {printf "--diagram %s ", $1}' docs/diagrams/render-manifest.tsv) && bash scripts/render-diagrams.sh --check --all && test "$(rg -c '^```mermaid' README.md || echo 0)" -eq 0 && python3 scripts/verify-comments.py check</automated>
    <human-check>Optional at end of phase: open each infra PNG at its embed width and confirm the smallest label is readable without zoom.</human-check>
  </verify>
  <done>Every standalone diagram passes all diagram-scoped guard rules and `--check --all` exits 0. The physical view keeps arch annotations on every node. README embeds the canonical physical PNG and holds no inline copy. The Maintenance Note matches the new layout. Each element of the three diagrams has a citation or a could-not-verify row.</done>
</task>

<task type="auto">
  <name>Task 5: Inline pass over docs/learning (tiered per D5), close the drift todo, prove the guard fails then passes</name>
  <files>docs/learning/00-README.md, docs/learning/01-domain-model-and-schema.md, docs/learning/02-persistence-and-queries.md, docs/learning/03-optimistic-locking.md, docs/learning/04-service-layer-and-access-control.md, docs/learning/05-api-layer.md, docs/learning/06-security-and-sessions.md, docs/learning/07-events-and-activity-feed.md, docs/learning/08-testing-strategy.md, docs/learning/09-build-quality-and-ci.md, docs/learning/10-infrastructure-and-deployment.md, docs/learning/11-observability.md, README.md, .planning/todos/pending/2026-09-05-seven-diagrams-drift-from-the-pinned-renderer.md → .planning/todos/completed/, .planning/todos/pending/2026-10-05-learning-guide-describes-the-compose-era-deployment.md</files>
  <action>
    Work the inline verdict table row by row. Line numbers are from planning time; re-locate each block by its heading. Do not touch docs/learning/kafka-architecture-overview.md or docs/wiki/.

    Drops (14 blocks, per D5). Remove the fence. Then read the surrounding section and make sure the facts the diagram showed are stated in prose: add at most one sentence, and remove any "the diagram below/above" wording. For 04:67, cite the ArchUnit test and state only what it actually enforces (find it with serena or rg in src/test). For 01:852, replace the block with a three-item dated list holding the same dates. For 06:233, link to docs/diagrams/scenarios/signin.png. For the five Compose-era blocks (06:942, 09:649, 10:207, 10:790, 11:54), use one sentence: the section describes the Docker Compose deployment the k3s cutover replaced on 2026-09-26, with a link to the current PNG (scenarios/inbound-packet-path, scenarios/push-to-deploy, physical/production-host, scenarios/push-to-deploy, physical/production-host respectively). Commit as `docs(quick-261005-sun): drop class-level and decommissioned inline diagrams from the learning guide`.

    Keeps (6 blocks, per D4 and D5). Each gets a verification table in the SUMMARY.
    (a) 00:66: replace Caddy with Traefik (k3s ingress, TLS and rate limit) and Promtail with Alloy, open with the init line and the TB keyword, and verify the edges against k8s/ and application.properties.
    (b) 01:64 erDiagram: verify every column, type, key, nullability and version annotation against src/main/resources/db/migration/V1-V9 by rg. Fix mismatches.
    (c) 03:331: relabel the service lifeline to "API (version check)", and verify against TaskService's update method (serena), GlobalExceptionHandler's optimistic-lock handler and TaskLockingTest.
    (d) 05:732: relabel the entry-point and handler lifelines to their roles (401 entry point in the filter chain; exception handler, @ControllerAdvice), and confirm every status in the second arm has a handler.
    (e) 07:74: rename the class-named lifelines to roles (publisher thread kafka-publish-*, consumer in group activity-log), merge the recorder into the consumer lifeline (same thread), and verify the timeouts, partition count, retry count and spacing, the DLT name and lookup-only schema registration against application.properties and KafkaConsumerConfig. Reuse T1's citations where they are identical.
    (f) 09:427: add the comment-policy gate in hook order (gitleaks, comment policy, spotlessCheck, fastTest), open with the init line and the TB keyword, verify the exit-code arms against .githooks/pre-commit, and correct the adjacent "three gates" prose here and in README.md's Quality & security gates paragraph.
    Commit as `docs(quick-261005-sun): verify and fix the kept learning-guide diagrams`.

    Todos and the regression demo (per D7). File .planning/todos/pending/2026-10-05-learning-guide-describes-the-compose-era-deployment.md with the per-chapter Caddy/Compose/Promtail counts measured at planning time (re-measure them), naming chapters 00, 05, 06, 07, 08, 09, 10, 11. `git mv` the drift todo to completed/ with a resolution line (every diagram re-rendered under the pinned image, `--check --all` green). Then run the planted-break demo on a clean tree (assert `git status --porcelain docs` is empty first). First, `git mv` docs/diagrams/process/activity-pipeline.png to docs/diagrams/ → `check` must exit 1 with outside-view-folder and missing-twin lines → `git mv` it back → exit 0. Second, append a link to diagrams/scenarios/does-not-exist.png to docs/ARCHITECTURE.md → exit 1 with dangling-reference → `git checkout -- docs/ARCHITECTURE.md` → exit 0. Paste all four outputs and exit codes into the SUMMARY. Commit the todos as `docs(quick-261005-sun): close the diagram drift todo, file the learning-guide rewrite`.

    Final report in the SUMMARY: the guard `report` table for all diagrams; the inline keep/drop table with outcomes; the list of docs/wiki references to renamed or dropped diagrams, found by rg in docs/wiki (pre-existing unresolved links, not edited); the wiki-lint before/after lines; and the statement that no Java or resources changed (`git diff --stat main...HEAD -- src build.gradle settings.gradle` empty), so the per-commit fastTest is the test evidence. Do not merge to main; stop at the merge gate and show the diff summary.
  </action>
  <verify>
    <automated>python3 scripts/verify-diagrams-selftest.py && python3 scripts/verify-diagrams.py check && bash scripts/render-diagrams.sh --check --all && python3 scripts/verify-comments.py check && ./gradlew spotlessCheck < /dev/null && python3 -c "import glob,sys; n=sum(l.strip()=='\`\`\`mermaid' for f in glob.glob('docs/learning/[01]*.md') for l in open(f)); print('inline mermaid fences:', n); sys.exit(0 if n==6 else 1)" && test -z "$(git diff --stat main...HEAD -- src build.gradle settings.gradle)" && test "$(python3 .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py docs | tail -1)" = "0 fidelity suspect(s), 20 evidence error(s), 51 unreferenced raw file(s)" && test -z "$(git status --porcelain docs/diagrams)"</automated>
  </verify>
  <done>The full guard exits 0 over standalone and inline diagrams, and the selftest and `--check --all` pass. Exactly 6 inline blocks remain in the tracked learning chapters, each verified. Dropped blocks leave no dangling prose. The planted-break demo shows exit 1 → 0 twice. The drift todo is closed and the learning-guide todo is filed. The SUMMARY holds every verification, legibility and carry-over table plus the wiki reference report. Nothing is merged.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| working tree → verify-diagrams.py | the guard reads repo files; credential files (.env, .env.prod) live in the same tree |
| .mmd source → pinned renderer container | Puppeteer renders repo-authored markup; check mode mounts docs/diagrams read-only |
| committed diagrams → public GitHub repo | infra topology labels are published |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-261005-sun-01 | Information disclosure | verify-diagrams.py file enumeration | medium | mitigate | Enumerate with `git ls-files` plus the excluded-prefix set, never a filesystem walk from the root, so gitignored credential files are never opened. Stated in the guard's Decisions block |
| T-261005-sun-02 | Information disclosure | rewritten physical/scenario labels | medium | mitigate | Every label must cite a committed source (verification tables), so nothing that is only on the VM or private enters the docs. gitleaks runs on every commit. No public IPs added |
| T-261005-sun-03 | Tampering | render-diagrams.sh check mode | low | mitigate | Keep the `:ro` source mount in check mode; the config is read through that same mount |
| T-261005-sun-04 | Repudiation | dropped diagrams (doc truth) | medium | mitigate | Per-diagram carry-over list in the SUMMARY (each fact carried or explicitly dropped as class-level detail) |
| T-261005-sun-05 | Tampering | file history of renamed diagrams | low | mitigate | Rename-only commits precede content rewrites. `git log --follow` count asserted in T1/T2 verify |
| T-261005-sun-06 | Denial of service | CI cost if the guard were wired in | low | accept | Not wired into CI (docs/** paths-ignore would have to go, triggering the deploy pipeline per docs commit). Local-only, documented |
| T-261005-sun-SC | Tampering | npm/pip/cargo installs | high | accept | No package-manager install in this plan: stdlib Python, the already-cached digest-pinned mermaid-cli image, and Pillow 12.3.0 already present (ad hoc previews only, never imported by committed code) |
</threat_model>

<verification>
- `python3 scripts/verify-diagrams-selftest.py` and `python3 scripts/verify-diagrams.py check` exit 0.
- `bash scripts/render-diagrams.sh --check --all` exits 0 under the pinned image and mermaid-config.json.
- The planted out-of-folder/missing-twin and dangling-reference breaks each exit 1, and restoring
  them exits 0 (SUMMARY holds the outputs).
- `rg` finds no legacy diagram name in README.md or docs outside wiki/raw. The wiki-lint summary
  line is unchanged from baseline.
- `python3 scripts/verify-comments.py check` and `./gradlew spotlessCheck` pass. No `src/` or
  build file changed, and the pre-commit fastTest ran on every commit.
</verification>

<success_criteria>
- docs/diagrams/ holds only process/, physical/, scenarios/ (plus the manifest and config). Every
  name follows `<subject>.{mmd,png}`.
- 7 standalone diagrams (more only if a split was required, with the reason recorded), each with
  W ≤ 1117, displayed height ≤ 1600 and text at least 12 CSS px at D=838, embedded with a matching
  width, and visually checked.
- 6 inline blocks kept and verified, 15 dropped with prose intact, 1 untouched.
- SUMMARY contains: a verification table per surviving diagram (13), the legibility table,
  carry-over lists for dropped diagrams, the guard's fail-then-pass evidence (pre-migration run in
  T1 and the planted breaks in T5), and the wiki reference report.
- Work stays on branch docs/diagram-grooming. No merge to main without the user's go-ahead.
</success_criteria>

<output>
Create `.planning/quick/261005-sun-groom-all-diagrams-kruchten-4-1-structur/261005-sun-SUMMARY.md` when done.
</output>

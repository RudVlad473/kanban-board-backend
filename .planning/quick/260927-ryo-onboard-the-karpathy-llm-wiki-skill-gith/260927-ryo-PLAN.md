---
phase: quick
plan: 260927-ryo
type: execute
wave: 1
depends_on: []
files_modified:
  - .claude/skills/karpathy-llm-wiki/SKILL.md
  - .claude/skills/karpathy-llm-wiki/LICENSE
  - .claude/skills/karpathy-llm-wiki/references/archive-template.md
  - .claude/skills/karpathy-llm-wiki/references/article-template.md
  - .claude/skills/karpathy-llm-wiki/references/index-template.md
  - .claude/skills/karpathy-llm-wiki/references/raw-template.md
  - .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py
  - .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/migration-map.tsv
  - .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/migrate_docs.py
  - .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/verify_migration.py
  - docs/raw/.gitkeep
  - docs/wiki/.gitkeep
  - docs/wiki/index.md
  - docs/wiki/log.md
  - docs/wiki/**   # 20 articles, exact paths in the Migration Map below
  - docs/raw/**    # 51 sources, exact paths in the Migration Map below
  - .claude/CLAUDE.md
autonomous: true
requirements: [QUICK-260927-RYO]
user_setup: []

estimate:
  tokens: 110000
  raw_tokens: 110000
  tasks: 3
  confidence: low

must_haves:
  truths:
    - "The karpathy-llm-wiki skill lives at .claude/skills/karpathy-llm-wiki/ and every installed file has the same git blob hash as the upstream tree at commit eafcc77001e496cc43499e4923b663aec722c813."
    - "Every tracked file under docs/history/, docs/incidents/ and docs/plans/backend-modernization/, plus docs/MOCKUP_FEATURE_GAP.md, has exactly one raw copy under docs/raw/<topic>/ with Source/Collected/Published metadata, and its text is unchanged apart from rebased link targets."
    - "Every doc in the wiki half of the Migration Map (8 top-level docs plus 12 docs/learning/ chapters) has an article under docs/wiki/<topic>/ with Sources/Raw/Updated metadata and an Overview heading. The body is identical to the original apart from rebased link targets."
    - "Every relative link in docs/wiki/**/*.md and docs/raw/**/*.md resolves to a file or directory on disk, and points at the new home of whatever the original link pointed at (unmoved targets such as docs/diagrams/ and docs/demo/ are reached at their current location)."
    - "docs/wiki/index.md lists every wiki article exactly once, grouped by topic, with an Updated date matching the article; docs/wiki/log.md holds exactly one entry, recording this migration."
    - "No original file under docs/ is modified or deleted by any commit of this task."
    - "The skill's own lint, run as `check_evidence.py docs`, reports 0 fidelity suspects, one 'article has no Raw field' error per migrated wiki article (the expected baseline under the operator-approved exception), and every raw file as unreferenced backlog."
    - "An agent opening this repo is told that the wiki root is docs/, not the repo root, and that the originals stay authoritative until a follow-on cleanup."
  artifacts:
    - ".claude/skills/karpathy-llm-wiki/SKILL.md"
    - ".claude/skills/karpathy-llm-wiki/scripts/check_evidence.py"
    - ".planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/migration-map.tsv"
    - ".planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/migrate_docs.py"
    - ".planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/verify_migration.py"
    - "docs/wiki/index.md"
    - "docs/wiki/log.md"
  key_links:
    - "migration-map.tsv -> migrate_docs.py (only source of truth for src -> dest; nothing is migrated that is not a row)"
    - "migration-map.tsv -> verify_migration.py (completeness: fresh `git ls-files` enumeration must equal the TSV's src column)"
    - "link rebasing: resolve against the source's old dir -> map through the TSV -> relativize from the destination's dir (a wrong depth here silently breaks ~1,700 links)"
    - "wiki article metadata header -> check_evidence.py parse_document (header = the contiguous blockquote directly after the first H1; the Raw line must contain no parenthesised .md path or the script reads it as a broken Raw link)"
    - ".claude/CLAUDE.md -> future skill invocations (without the docs/ root pointer, the next 'add to wiki' creates raw/ and wiki/ at the repo root)"
---

<objective>
Install the karpathy-llm-wiki skill in this repo and move the contents of docs/ into the
skill's raw/wiki layout, with the layout rooted at `docs/` (so `docs/raw/` and `docs/wiki/`).
All originals stay in place.

Purpose: turn docs/ from a flat mix of living references, dated session records and point-in-time
snapshots into the skill's two-layer model. Dated records and snapshots go into immutable
`docs/raw/` sources. Living references go into `docs/wiki/` articles with an index and a log, so a
later compile pass and the skill's lint have a real structure to work on. The work must be
link-consistent and provably faithful, so the operator can review it before a follow-on task
deletes the originals.

Output: the vendored skill (7 files, hash-verified), 20 wiki articles, 51 raw sources, index.md,
log.md, a CLAUDE.md pointer to the docs/ root, and the three migration artifacts in this quick
task's directory. The follow-on task re-runs those three artifacts right before deleting the
originals.

Planning-time scope findings. The executor records each of these in SUMMARY.md.
1. docs/ holds four top-level files the operator's list did not name: DIAGRAM_CONVENTIONS.md,
   LOCAL_DEV.md, SESSION_LESSONS.md and MOCKUP_FEATURE_GAP.md. All four were added between
   2026-08-01 and 2026-08-08, so they predate the conversation. This plan includes them under a
   stated assumption: the goal is "migrate docs/", and leaving 4 of 9 top-level docs behind would
   make the follow-on deletion ambiguous. The first three are living references and go to wiki/.
   MOCKUP_FEATURE_GAP.md is a single-day (2026-08-08) snapshot comparing mock-ups to the API as it
   was then, so it goes to raw/. Every one of them is an additive copy that can be deleted on its
   own if the assumption is wrong.
2. The upstream README's installer (`npx add-skill`, which translates to `pnpm dlx add-skill`
   under the user's pnpm preference) is deprecated. npm's add-skill@2.0.0 describes itself as
   "DEPRECATED: Use 'npx skills add' instead". The operator's allowed fallback, a manual copy, is
   used instead. See Approaches.
3. Pushing this work triggers deploy.yml. The vendored `LICENSE` and `scripts/check_evidence.py`
   fall outside deploy.yml's paths-ignore (`docs/**`, `**/*.md`, `.planning/**`, `k8s/**`). The
   triggered run builds a new app image, and Flux's ImagePolicy then rolls out production and
   nonprod. D-08's 24h evaluation window runs until 2026-09-28T10:44:46Z, so a push before then
   lands a rollout inside that window. The executor does not push. The operator decides when.
</objective>

<execution_context>
@~/.claude/gsd-core/workflows/execute-plan.md
@~/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.planning/STATE.md
@.claude/CLAUDE.md
@docs/SESSION_LESSONS.md

Do NOT read the docs being migrated into context. The 12 learning chapters alone are about 13,500
lines. Scripts do all copying and rewriting. Inspect results with `sed -n 1,15p <file>` spot
checks and the verifier's output.

## Mechanism (core data flow, 3 sentences)

`migration-map.tsv` names each source path, its destination and its kind (wiki, raw-md or
raw-code). `migrate_docs.py` opens each source read-only and adds the skill's metadata header (plus
an `## Overview` heading for wiki articles, or a 4-space-indented body for non-markdown files). It
rebases every relative link by resolving it from the source's old directory, mapping it through
the TSV and relativizing it from the destination, then regenerates `docs/wiki/index.md` and
`docs/wiki/log.md` from the TSV. `verify_migration.py` is a separate implementation that
independently re-derives what each destination must contain: header shape, body equal to the
original apart from link targets, and each link resolving to the mapped equivalent of its
original target. It exits non-zero on any mismatch.

## Planning-time observations (live, 2026-09-27; re-checked by the verifier at execution)

- Upstream: github.com/Astro-Han/karpathy-llm-wiki, MIT, default branch main, HEAD
  `eafcc77001e496cc43499e4923b663aec722c813`. The files vendored are SKILL.md, LICENSE,
  references/{archive,article,index,raw}-template.md and scripts/check_evidence.py. The README,
  assets/ (a 283 KB png), examples/ and tests/ are not needed to use the skill and are left out.
- check_evidence.py uses only re/sys/dataclasses/pathlib, reads with `read_text`, never writes, and
  needs Python 3.10+ (local python3 is 3.14.4). Called as `check_evidence.py docs`, it treats
  `docs/` as the project root, so `docs/wiki/` and `docs/raw/` are its wiki/ and raw/. Three of its
  behaviors shape this plan:
  (a) An article whose header yields no `(...md)` link on its `> Raw:` line is reported as
  "article has no Raw field".
  (b) Its `parse_document` drops fenced code blocks from raw bodies entirely, so fenced content is
  invisible to Grounding-Invariant checks.
  (c) It lists every raw/**/*.md that no article references, minus files logged as a triage
  disposition. This migration logs no such dispositions, so all 51 raws stay visible as compile
  backlog.
- Relative links in the 20 wiki-bound originals: about 1,700, and 0 of them broken today. Only 1
  sits inside an inline code span. Directory-target links exist: `history/` (INFRA_RUNBOOK.md),
  `plans/backend-modernization/` (ARCHITECTURE.md) and `../2026-09-05-nonprod-syn-loss/` (an
  incident README). Raw-bound markdown sources hold about 61 relative links.
- Every markdown source starts with an H1 on line 1. None has CRLF line endings. All end in a
  trailing newline. Every wiki-bound doc has intro text before its first `## ` and none already has
  an `## Overview` heading. So adding the Overview heading needs no new prose and cannot collide
  with an existing `#overview` anchor.
- `.gitleaks.toml` allowlists are all rule/match-scoped (`regexTarget = "match"`), none path-scoped,
  so copies are scanned exactly as the originals were. `.gitleaks-baseline.json` holds one
  fingerprint (src/main/resources/application.properties), which is not in docs/.
- `git status --porcelain -- docs` was empty. Unrelated working-tree changes exist
  (`.planning/config.json`, `.zed/`, `.planning/phases/13-introduce-kubernetes/.gitkeep`). Never
  stage them. Stage explicit paths only, never `git add -A` or `git add .`.

## Approaches considered

Migration mechanics:
- A (picked): a committed map (TSV), a migration script and an independent verifier.
- B: the executor hand-copies each file and fixes links with the Edit tool.
- C: `git mv` the originals into the new layout, a true move that keeps `git log --follow`.

Skill install:
- D (picked): vendor the needed upstream files, fetched by pinned commit SHA via `gh api`, and
  verify git blob hashes.
- E: `pnpm dlx add-skill Astro-Han/karpathy-llm-wiki`, the upstream README's `npx` command
  translated to pnpm.

Non-markdown artifacts (probe.csv, probe.sh, *.txt, *.sql):
- F (picked): a 4-space-indented code block under the raw metadata header.
- G: a fenced code block.
- H: leave the files in their original formats inside raw/.

Links inside raw sources:
- I (picked): rebase relative link targets exactly like wiki links. Link text and all other text
  stay verbatim.
- J: keep the files byte-verbatim.

| Approach | Pros / Cons | Why picked |
|----------|-------------|------------|
| A script + map + verifier | + deterministic and re-runnable, so the follow-on can regenerate right before deleting originals. + about 1,700 links rewritten by rule, not by hand. + the 13.5K learning lines never enter agent context. − about 350 lines of throwaway-grade Python to write. | Picked. It is the only option where "faithful" and "link-consistent" are machine-checked rather than asserted. |
| B hand-copy + Edit | + no new code. − needs every doc read into context, far over budget. − about 1,700 manual link edits with no systematic check. | Rejected. Unverifiable at this scale. |
| C git mv | + preserves history. + no duplicate copies. − deletes the originals in this pass, which the operator explicitly ruled out. − breaks every external referrer at once. | Rejected. It is the one-way step the operator deferred. |
| D vendor by pinned SHA | + content-addressed and reproducible. + no third-party installer code runs. + lands exactly at .claude/skills/karpathy-llm-wiki/. − upgrades are manual. | Picked. It is the operator's allowed fallback and the stronger supply-chain posture. |
| E pnpm dlx add-skill | + one command. − add-skill@2.0.0 is deprecated on npm ("Use 'npx skills add' instead"). − it runs unpinned installer code. − its target path would need verifying anyway. − it would trigger the package-legitimacy blocking checkpoint. | Rejected. |
| F indented block | + renders as code on GitHub and in IDEs. + stays visible to check_evidence.py, so a later compile can cite probe timings or iperf numbers. + reversible by stripping exactly 4 leading spaces. − no syntax highlighting. | Picked. |
| G fenced block | + syntax highlighting. − check_evidence.py drops fenced lines, so a future article citing those numbers would show false fidelity suspects. | Rejected. |
| H original formats | + zero transformation. − check_evidence.py only globs raw/**/*.md, so these files would never appear in its inventory. − the raw template is markdown. | Rejected. |
| I rebase raw links | + about 61 links keep working now and after the originals are deleted. + check_evidence.py strips link targets before matching, so evidence is unaffected. − a byte-level departure from the source, recorded in log.md. | Picked. |
| J byte-verbatim | + purest immutability. − every relative link was written relative to docs/history/ and similar folders, so they would all dangle immediately. | Rejected. |

Non-obvious trade-offs:
- Split-brain window (state invalidation). Until the follow-on lands, every migrated doc exists
  twice. Plan 13-10 is next and will likely edit docs/INFRA_RUNBOOK.md and docs/INFRA_ARCHITECTURE.md
  and add a docs/history/ entry, and those edits will not reach docs/wiki/ or docs/raw/.
  Mitigations: CLAUDE.md names the originals as authoritative (Task 3). migrate_docs.py is
  idempotent, and the follow-on re-runs it right before deleting. verify_migration.py's
  completeness and faithfulness checks fail loudly on a stale or missing copy. Do not hand-edit the
  generated copies before the follow-on.
- Repo size. About 1.7 MB (by `du`) of text is duplicated until the follow-on. The copies differ
  from their originals only in headers and link targets, so git packs them as small deltas.
- History trail. Copies do not inherit `git log --follow`. The trail is kept in text instead: each
  raw header records the source path and git blob, and each wiki Sources line records the source
  path.
- Lint baseline noise. check_evidence.py will report 20 "article has no Raw field" errors until the
  compile pass. The CLAUDE.md pointer states this exact baseline, so a future reader does not treat
  it as a regression, and does not "fix" it by linking raws that were never compiled.
- Deploy trigger. See objective finding 3 and T-ryo-06.
- Performance. Both scripts are O(total bytes), about 1.7 MB, and finish in well under a second. The
  pre-commit hook (gitleaks, then spotlessCheck plus fastTest, about 4 minutes) is the real cost:
  three task commits mean three hook runs.

## Migration Map (authoritative, write it as migration-map.tsv)

TSV columns: `src`, `dest`, `kind`, `summary`. Tab-separated, with one header row. `summary` is
filled for wiki rows only. `kind` is one of `wiki`, `raw-md`, `raw-code`.

Wiki rows. The Updated date is the date of the src file's last git commit, computed at run time.

| src | dest | summary |
|-----|------|---------|
| docs/ARCHITECTURE.md | docs/wiki/architecture/application-architecture.md | Engineering mechanisms behind the README summary, each claim tied to the class, task, migration or test that proves it. |
| docs/AUTH_FLOWS.md | docs/wiki/architecture/auth-flows.md | What a client observes on the signup and signin routes, and what silently breaks an automated test suite driving them. |
| docs/CODE_STYLE.md | docs/wiki/conventions/code-style.md | Append-only Java code-style rules that agents and contributors follow in this repository. |
| docs/DIAGRAM_CONVENTIONS.md | docs/wiki/conventions/diagram-conventions.md | How architecture diagrams snap to Kruchten's 4+1 view model, plus flowchart layout rules. |
| docs/SESSION_LESSONS.md | docs/wiki/conventions/session-lessons.md | Append-only operational lessons from running GSD workflows here: git hygiene, push cadence, hooks, host memory contention. |
| docs/INFRA_ARCHITECTURE.md | docs/wiki/infra/infra-architecture.md | Production deployment topology after the k3s cutover: single-node k3s on a Netcup VPS, Flux, Traefik, cert-manager, self-hosted Postgres and Redpanda. |
| docs/INFRA_RUNBOOK.md | docs/wiki/infra/infra-runbook.md | The actual provisioned state of the production VM and how it is locked down, with no secrets recorded. |
| docs/LOCAL_DEV.md | docs/wiki/infra/local-dev-stack.md | Running the local docker-compose development stack, why it is shaped that way, and what it is not. |
| docs/learning/00-README.md | docs/wiki/learning/00-README.md | How to read the learning guide: audience, chapter order and the Simplified Technical English it uses. |
| docs/learning/01-domain-model-and-schema.md | docs/wiki/learning/01-domain-model-and-schema.md | The five domain entities, their relationships, primary keys and PostgreSQL schema. |
| docs/learning/02-persistence-and-queries.md | docs/wiki/learning/02-persistence-and-queries.md | Reading and writing the Kanban graph through Spring Data JPA and Hibernate, where request cost is a count of SQL statements. |
| docs/learning/03-optimistic-locking.md | docs/wiki/learning/03-optimistic-locking.md | Detecting conflicting writes with versions: stale writes get 409, truly parallel losers get 500 (a known gap). |
| docs/learning/04-service-layer-and-access-control.md | docs/wiki/learning/04-service-layer-and-access-control.md | Where every business rule and the only access control live: ownership checks, ordering, uniqueness, cascades, event publication. |
| docs/learning/05-api-layer.md | docs/wiki/learning/05-api-layer.md | Turning HTTP into service calls and results into JSON; the status-code and error-code contract the frontend reads. |
| docs/learning/06-security-and-sessions.md | docs/wiki/learning/06-security-and-sessions.md | Caller identity, server-side sessions, refusing unauthenticated requests and limiting password attempts. |
| docs/learning/07-events-and-activity-feed.md | docs/wiki/learning/07-events-and-activity-feed.md | Domain events to Kafka and their consumption into each board's activity log: delivery semantics, idempotency, schema evolution. |
| docs/learning/08-testing-strategy.md | docs/wiki/learning/08-testing-strategy.md | Proving every layer against real Postgres, a real Spring context and a real Kafka-protocol broker instead of mocks. |
| docs/learning/09-build-quality-and-ci.md | docs/wiki/learning/09-build-quality-and-ci.md | From source tree to a tested, scanned, pinned Docker image, and the gates deciding what reaches production. |
| docs/learning/10-infrastructure-and-deployment.md | docs/wiki/learning/10-infrastructure-and-deployment.md | The physical deployment on one Netcup VPS, and the guarantees and limits of that box. |
| docs/learning/11-observability.md | docs/wiki/learning/11-observability.md | Host, container, Postgres and Redpanda metrics and container logs, shown in self-hosted Grafana. |

The learning filenames are kept identical: the numeric prefix is the reading order, and keeping
the names leaves the roughly 71 intra-chapter links unchanged.

Index topic descriptions, one line under each `## <topic>` heading:
- architecture: How the Spring Boot application is built: layering, access control, persistence, errors, and the authentication flows a client observes.
- conventions: How work in this repo is written and run: Java code style, diagram conventions and GSD session lessons.
- infra: Where and how the system runs: production infrastructure, its operations runbook and the local development stack.
- learning: A numbered study guide explaining each layer's decisions, read in order from 00.

Raw rows. Published = the date prefix of the destination filename, derived by the rule stated for
each group. All slugs are at most 60 characters.

- `raw-md`, 31 rows: each `docs/history/<name>.md` (every file except README.md) maps to
  `docs/raw/infra-history/<name>.md`, keeping the same filename so the entries' links to each other
  still resolve. Published = the filename's date. Observed at planning time:
  2026-08-16-automated-deploy-task-2-and-task-3, 2026-08-16-deploy-user-setup-task-1,
  2026-08-16-manual-deploy-task-1, 2026-08-16-manual-deploy-task-2-schema-registry-cutover-verification,
  2026-08-16-manual-deploy-task-3-redpanda-resource-caps-measured, 2026-08-17-decommission-record-task-3,
  2026-08-17-external-network-audit-task-1, 2026-08-17-log-rotation-observation-task-2,
  2026-08-18-nonprod-bring-up, 2026-08-18-nonprod-ci-deploy-identity-and-environment-scoped-secrets,
  2026-08-18-nonprod-ci-health-gate-and-image-retention, 2026-08-18-nonprod-reset-endpoint,
  2026-08-18-nonprod-resource-measurement, 2026-08-19-automated-avro-schema-registration,
  2026-08-20-digest-pinned-deploy-actions-task-1-live-tracer, 2026-08-26-ci-flyway-verification-over-ssh,
  2026-08-26-decommission-record-neon, 2026-08-26-postgres-memory-profile-correction,
  2026-08-26-provisioning-script-hardening, 2026-08-26-self-hosted-postgres-cutover,
  2026-08-26-self-hosted-postgres-resource-measurement, 2026-09-02-triage-netcup-scp-screen-console,
  2026-09-07-log-aggregation, 2026-09-07-monitoring-role-and-metrics-targets,
  2026-09-08-caddy-resource-measurement, 2026-09-08-deploy-scp-coverage-gap-quick-task-260908-sj9,
  2026-09-08-observability-stack-resource-measurement,
  2026-09-12-public-grafana-dashboards-rendered-no-data-debug-session,
  2026-09-25-k3s-install-and-interim-bridge, 2026-09-25-nonprod-on-k3s, 2026-09-26-production-cutover-to-k3s.
- `raw-md`: docs/history/README.md maps to docs/raw/infra-history/2026-09-25-infrastructure-history-index.md.
  Published is 2026-09-25, the reorg date its own intro states.
- Incidents. Published = the incident directory's date.
  - `raw-md`: docs/incidents/2026-09-05-nonprod-syn-loss/README.md → docs/raw/incidents/2026-09-05-nonprod-syn-loss.md
  - `raw-code`: docs/incidents/2026-09-05-nonprod-syn-loss/netcup-ticket.txt → docs/raw/incidents/2026-09-05-nonprod-syn-loss-netcup-ticket.md
  - `raw-code`: docs/incidents/2026-09-05-nonprod-syn-loss/probe.csv → docs/raw/incidents/2026-09-05-nonprod-syn-loss-probe-results.md
  - `raw-code`: docs/incidents/2026-09-05-nonprod-syn-loss/probe.sh → docs/raw/incidents/2026-09-05-nonprod-syn-loss-probe-script.md
  - `raw-md`: docs/incidents/2026-09-24-netcup-network-diagnostics/README.md → docs/raw/incidents/2026-09-24-netcup-network-diagnostics.md
  - `raw-code`: docs/incidents/2026-09-24-netcup-network-diagnostics/diagnostics.txt → docs/raw/incidents/2026-09-24-netcup-network-diagnostics-output.md
- Plans. The filenames carry no date, so Published = the src file's last git commit date (the
  captured revision). These values were observed at planning time. If `git log -1` now disagrees,
  the verifier fails, and the executor renames the destination to match.
  - `raw-md`: 01-kafka-activity-feed.md → docs/raw/backend-modernization-plan/2026-08-09-01-kafka-activity-feed.md
  - `raw-md`: 02-n-plus-one-optimistic-locking.md → .../2026-08-01-02-n-plus-one-optimistic-locking.md
  - `raw-code`: 02-optimistic-locking-ddl.sql → .../2026-08-17-02-optimistic-locking-ddl.md
  - `raw-code`: 03-activity-log-ddl.sql → .../2026-08-05-03-activity-log-ddl.md
  - `raw-md`: 03-flyway-openapi.md → .../2026-08-05-03-flyway-openapi.md
  - `raw-code`: 04-password-hash-not-null-ddl.sql → .../2026-08-05-04-password-hash-not-null-ddl.md
  - `raw-md`: 04-redis.md → .../2026-08-01-04-redis.md
  - `raw-md`: 05-testcontainers.md → .../2026-08-06-05-testcontainers.md
  - `raw-md`: 06-observability.md → .../2026-08-01-06-observability.md
  - `raw-md`: 07-kubernetes-stretch.md → .../2026-08-01-07-kubernetes-stretch.md
  - `raw-md`: README.md → .../2026-08-17-backend-modernization-plan-overview.md
  - `raw-md`: STATUS.md → .../2026-08-17-backend-modernization-status.md
  (All src paths are under docs/plans/backend-modernization/, and all dest paths under
  docs/raw/backend-modernization-plan/.)
- `raw-md`: docs/MOCKUP_FEATURE_GAP.md → docs/raw/product/2026-08-08-mockup-vs-backend-feature-gap.md. Published is 2026-08-08.

Totals as of planning: 20 wiki rows and 51 raw rows (32 infra-history, 6 incidents, 12 plan, 1
product). Unmoved and never touched: docs/diagrams/**, docs/demo/**.
</context>

<tasks>

<task type="tracer">
  <name>Task 1: Vendor the skill, build map + migrator + verifier, migrate a 3-file tracer slice end-to-end</name>
  <files>.claude/skills/karpathy-llm-wiki/SKILL.md, .claude/skills/karpathy-llm-wiki/LICENSE, .claude/skills/karpathy-llm-wiki/references/archive-template.md, .claude/skills/karpathy-llm-wiki/references/article-template.md, .claude/skills/karpathy-llm-wiki/references/index-template.md, .claude/skills/karpathy-llm-wiki/references/raw-template.md, .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py, .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/migration-map.tsv, .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/migrate_docs.py, .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/verify_migration.py, docs/raw/.gitkeep, docs/wiki/.gitkeep, docs/wiki/index.md, docs/wiki/log.md, docs/wiki/architecture/auth-flows.md, docs/raw/infra-history/2026-09-26-production-cutover-to-k3s.md, docs/raw/incidents/2026-09-05-nonprod-syn-loss-probe-script.md</files>
  <precondition>`git status --porcelain -- docs` prints nothing, `gh auth status` succeeds, and `docker info` succeeds (the pre-commit hook's gitleaks step runs in Docker and refuses the commit without it).</precondition>
  <action>
Step 1: vendor the skill. For each of the 7 upstream paths (SKILL.md, LICENSE, the four
references/*.md templates, scripts/check_evidence.py), fetch the exact bytes at commit
eafcc77001e496cc43499e4923b663aec722c813. Use `gh api -H 'Accept: application/vnd.github.raw'
'repos/Astro-Han/karpathy-llm-wiki/contents/<path>?ref=<sha>'`, redirect the output into
.claude/skills/karpathy-llm-wiki/<path>, and create the parent directories first. Do not use the
add-skill or skills npm installers (see Approaches D/E: deprecated, runs unpinned code). Do not add
any file of your own inside the skill directory. Its file set must be exactly the upstream
subset, so the hash check proves it is untouched. Run `git hash-object` on each file and compare
against the blob sha that
`gh api 'repos/Astro-Han/karpathy-llm-wiki/git/trees/<sha>?recursive=1'` reports for the same path.
Read the vendored SKILL.md and the four templates now; the formats below implement them.

Step 2: scaffold. Following the skill's Initialization section, create docs/raw/.gitkeep and
docs/wiki/.gitkeep as empty files. index.md and log.md are generated in Step 4.

Step 3: write migration-map.tsv in this task's directory, with every row from the Migration Map
in context. Before writing, re-enumerate the sources with
`git ls-files docs/*.md docs/learning docs/history docs/incidents docs/plans/backend-modernization`.
If a tracked file exists that the map does not list, apply the map's rules:
- a dated record → raw/infra-history with the same name;
- an incident artifact → raw/incidents;
- a living reference → a wiki topic.
Record every such addition for the SUMMARY. Never silently skip a file.

Step 4: write migrate_docs.py (Python 3 standard library only). Behavior contract:
(a) Read the TSV. Accept an optional `--only <src> [<src> ...]` to process a subset. Open sources
read-only and never write to any src path. Read and write with `newline=''` so bytes are preserved.
(b) wiki kind. Write, in order:
  - the source's line 1 (its H1);
  - a blank line;
  - exactly three metadata lines:
    - `> Sources: kanban-board-backend <src>, <U>`, where U is the date of the src file's last git
      commit (`git log -1 --format=%ad --date=short -- <src>`);
    - `> Raw: none — primary document migrated as-is on <today>, a one-time operator-approved
      exception to the Grounding Invariant`. This line must contain no parentheses at all.
      check_evidence.py's Raw-link regex matches any parenthesised span ending in .md, and would
      then report a broken Raw link instead of the intended no-Raw baseline;
    - `> Updated: <U>`;
  - a blank line, then `## Overview`, then a blank line;
  - the source's text after line 1, with leading blank lines removed and link targets rebased per (e).
  This implements the article template's Sources/Raw/Updated shape per the operator's
  one-time-exception instruction. The existing intro paragraph becomes the Overview body. No new
  prose is written.
(c) raw-md kind. Write the source's H1; a blank line; `> Source: <src> in the kanban-board-backend
repository, git blob <git rev-parse --short HEAD:<src>>`; `> Collected: <today>`;
`> Published: <date from the rule in the map>`; a blank line; then the rest of the source with
leading blank lines removed and link targets rebased per (e). This is the raw-template shape, with
the source's own H1 used as the title rather than duplicated.
(d) raw-code kind. Write `# <parent-dir-name>: <filename>` (e.g. `# 2026-09-05-nonprod-syn-loss: probe.sh`),
a blank line, the same three raw metadata lines, a blank line, then every source line prefixed with
exactly four spaces. Empty lines stay empty. This is Approach F: a code block that
check_evidence.py can still see. Do not rebase anything inside it.
(e) Link rebasing applies to markdown only, and skips fenced code blocks and inline code spans.
Detect fences the way check_evidence.py's fence_opener and is_fence_closer do. Mask backtick spans
before matching.
  - Link forms: inline `](target)`, `](<target>)` and `](target "title")`; reference definitions
    `[label]: target`; HTML `src="..."` and `href="..."`.
  - Leave alone any target starting with `http:`, `https:`, `mailto:` or `#`.
  - Otherwise split off any `#fragment` and keep it verbatim, then resolve the path part against
    the src file's directory. If the result is a src in the TSV, use its dest. If it is a directory
    whose README.md is a src in the TSV, use that README's dest. Otherwise leave it unmoved
    (diagrams, demo, repo files, directories).
  - Rewrite the target as that path relative to the dest file's directory, plus the fragment.
    Change only the target characters; keep the surrounding syntax byte-identical.
(f) Regenerate docs/wiki/index.md from the TSV rows whose dest exists: `# Knowledge Base Index`,
then one `## <topic>` section per topic in alphabetical order. Each section has the topic's
one-line description, then the index-template table `| Article | Summary | Updated |` with one row
per article, sorted by dest filename. The Article cell is `[<H1 text without "# ">](<topic>/<file>.md)`;
the Summary comes from the TSV; Updated is the article's U.
(g) Regenerate docs/wiki/log.md as `# Wiki Log`, a blank line, and exactly one entry, headed
`## [<today>] migrate | docs/ relocated into raw/ and wiki/`. Its bullet lines record:
  - the skill source (repo plus full commit SHA);
  - the wiki article count, with the note that the metadata header and Overview heading were added,
    the body is unchanged, and there are no Raw links (the operator-approved one-time exception);
  - the raw source count, the four source areas, and the note that the sources are not yet compiled;
  - that relative link targets were rebased to the new locations and nothing else changed;
  - that the originals are left in place and stay authoritative until a follow-on cleanup.
  Counts come from the dests actually present. Abort without writing if log.md already exists and
  contains any `## [` heading other than a migrate entry. Never write a triage-disposition entry,
  because the raw backlog must stay visible to check_evidence.py.
Keep comments to the non-obvious why only (fences are skipped by check_evidence.py; the Raw line
has no parentheses). Follow the user's code-comment rules: no planning-token citations in code
comments.

Step 5: write verify_migration.py as a separate implementation. It must not import migrate_docs.py:
the point is two independent derivations. It accepts the same optional `--only` and exits non-zero
on any failure. For every failure it prints the file, the check and the offending value. It always
ends with a count line of the form `checked: <W> wiki, <R> raw, <L> links (<P> pending); failures: <F>`.
Checks:
(1) Completeness, skipped under --only: the fresh `git ls-files` enumeration from Step 3 equals the
TSV src set, and dest paths are unique.
(2) Layout: each wiki dest matches `docs/wiki/<topic>/<name>.md` exactly one level deep; each raw
dest matches `docs/raw/<topic>/YYYY-MM-DD-<slug>.md` with slug at most 60 characters of
[a-z0-9-].
(3) Header shape, per kind, exactly as in Step 4. U must equal the src file's last-commit date.
Published must follow the map's rule for that group. Collected must equal the log entry's date.
(4) Faithfulness. For wiki and raw-md files: take the text after the inserted block (after
`## Overview` for wiki), strip leading blank lines, and normalize every inline link target to empty
and every ref-def target to empty. The result must equal the original after its H1, with the same
normalization. For raw-code files: remove exactly four leading spaces from each non-empty body
line; the result must equal the source bytes.
(5) Links in each generated markdown file. Extract links the same way (skip fences and inline code)
from both the original and the new file. The counts must be equal. Pair them by position. For each
pair, expected = the original's resolved target, pushed through the TSV mapping (including the
directory-README rule), plus the original fragment. The new link's resolved target must equal
expected, and must exist on disk. Under --only, an expected target that is a TSV dest not yet
generated counts as pending, not a failure.
(6) Originals untouched. Each src's working-tree bytes equal `git show HEAD:<src>`, and
`git log --format=%s -- <src>` contains no subject with `260927-ryo`.
(7) index.md: the set of linked articles equals the wiki dests present (under --only, the migrated
subset); each row's Updated equals the article's `> Updated:`; each row sits under the `## <topic>`
matching its directory, and each topic section has a description line.
(8) log.md has exactly one entry heading, and it is the migrate entry.
(9) No path under docs/wiki/ is deeper than `docs/wiki/<topic>/<file>`.

Step 6: tracer run. Run migrate_docs.py with `--only docs/AUTH_FLOWS.md
docs/history/2026-09-26-production-cutover-to-k3s.md docs/incidents/2026-09-05-nonprod-syn-loss/probe.sh`,
which covers one row of each kind. Then run the verifier with the same --only, then the skill's own
lint (see verify). Spot-check with `sed -n 1,14p` on each of the three generated files and on
index.md. Expected pending links: AUTH_FLOWS's links to ARCHITECTURE.md and DIAGRAM_CONVENTIONS.md,
and the cutover entry's link to SESSION_LESSONS.md.

Step 7: commit. Stage only the files listed above, by explicit path. Use a message like
`feat(260927-ryo): vendor karpathy-llm-wiki skill, migrate tracer slice into docs/raw and docs/wiki`
and put the upstream SHA in the body. Do not push. If the pre-commit hook fails in spotlessCheck or
fastTest because the Gradle daemon was killed under host memory pressure, STOP. Report whether the
hook's gitleaks step printed "No secrets found in staged diff." and ask the operator for fresh
`--no-verify` authorization that covers this one commit only. Never assume it, and never reuse an
earlier authorization. A gitleaks finding is never bypassed.
  </action>
  <verify>
    <automated>cd /home/andre/dev/kanban-board-backend && Q=.planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith && S=.claude/skills/karpathy-llm-wiki && T=$(gh api 'repos/Astro-Han/karpathy-llm-wiki/git/trees/eafcc77001e496cc43499e4923b663aec722c813?recursive=1') && for f in SKILL.md LICENSE references/archive-template.md references/article-template.md references/index-template.md references/raw-template.md scripts/check_evidence.py; do [ "$(printf '%s' "$T" | python3 -c "import json,sys;print(next(e['sha'] for e in json.load(sys.stdin)['tree'] if e['path']=='$f'))")" = "$(git hash-object $S/$f)" ] || { echo "HASH MISMATCH $f"; exit 1; }; done && [ "$(find $S -type f | wc -l)" -eq 7 ] && head -3 $S/SKILL.md | grep -qx 'name: karpathy-llm-wiki' && ! rg -n 'subprocess|socket|urllib|requests|write_text|write_bytes|unlink|rmtree|os\.system' $S/scripts/check_evidence.py && python3 $Q/verify_migration.py --only docs/AUTH_FLOWS.md docs/history/2026-09-26-production-cutover-to-k3s.md docs/incidents/2026-09-05-nonprod-syn-loss/probe.sh && python3 $S/scripts/check_evidence.py docs | tail -1 | grep -qx '0 fidelity suspect(s), 1 evidence error(s), 2 unreferenced raw file(s)' && test -f docs/raw/.gitkeep && test -f docs/wiki/.gitkeep && echo TRACER-OK</automated>
  </verify>
  <done>All 7 vendored files match upstream blob hashes at eafcc77 and the skill directory holds
nothing else. The three tracer destinations exist with the specified headers. The verifier passes
under --only, reporting a nonzero link count and exactly the expected pending targets. The skill's
own lint parses the docs/ root and reports 0/1/2. The commit contains only this task's paths, and
docs/ originals are byte-identical to HEAD.</done>
</task>

<task type="auto">
  <name>Task 2: Migrate every remaining source, regenerate index/log, prove the verifier bites</name>
  <files>docs/wiki/** (20 articles), docs/raw/** (51 sources), docs/wiki/index.md, docs/wiki/log.md, .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/migration-map.tsv (only if drift rows are needed)</files>
  <precondition>Task 1's commit exists and `git status --porcelain -- docs` shows no modification to any tracked file under docs/.</precondition>
  <action>
Run migrate_docs.py with no --only, so every row is processed. This also regenerates the three
tracer files, which is expected to produce no diff unless today's date changed. Then run
verify_migration.py with no --only and fix anything it reports. A failure means a bug in
migrate_docs.py, the TSV or the verifier. Fix the cause, never the symptom, and never hand-edit a
generated file. If a failure is a plan-time value that drifted (a plan file's last-commit date, or
a new tracked source), update the TSV row by the map's rules and record it for the SUMMARY.

Negative controls. These prove the gate is not vacuous: a check that passes on corrupted output
covers nothing. Make each corruption in the working tree only, run the verifier, confirm it fails
and names the exact file and check, then re-run migrate_docs.py to regenerate, and confirm the
verifier passes again.
(i) Change one relative link target in docs/wiki/learning/05-api-layer.md to a nonexistent path.
The verifier must fail on the link check.
(ii) Change one word in the body of docs/raw/infra-history/2026-08-26-self-hosted-postgres-cutover.md.
The verifier must fail on the faithfulness check.
(iii) Delete four leading spaces from one line of
docs/raw/incidents/2026-09-05-nonprod-syn-loss-probe-results.md. The verifier must fail on
raw-code faithfulness.
Record each failing output line for the SUMMARY.

Then run the skill's own lint over the docs/ root (see verify). Expect 0 fidelity suspects. Every
evidence error should be "article has no Raw field", one per wiki article. Every raw file should be
listed as unreferenced; that is the intended compile backlog. Check the verifier's reported link
count against the planning-time figure of about 1,700 wiki links plus about 61 raw links. A count
far below that means the extraction is missing link forms; investigate before committing.

Spot-check rendering cheaply: `sed -n 1,14p` on docs/wiki/infra/infra-runbook.md,
docs/wiki/learning/00-README.md and docs/raw/backend-modernization-plan/2026-08-17-02-optimistic-locking-ddl.md,
and `sed -n 1,40p docs/wiki/index.md`.

Commit only docs/raw/, docs/wiki/ and the TSV if it changed, by explicit path, with a message like
`docs(260927-ryo): migrate docs/ into docs/raw and docs/wiki (20 articles, 51 sources)`. Do not
push. The pre-commit memory-pressure rule from Task 1 applies unchanged: stop and ask for fresh,
single-commit authorization.
  </action>
  <verify>
    <automated>cd /home/andre/dev/kanban-board-backend && Q=.planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith && python3 $Q/verify_migration.py && W=$(awk -F'\t' 'NR>1 && $3=="wiki"' $Q/migration-map.tsv | wc -l) && R=$(awk -F'\t' 'NR>1 && $3!="wiki"' $Q/migration-map.tsv | wc -l) && OUT=$(python3 .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py docs) && printf '%s\n' "$OUT" | tail -1 | grep -qx "0 fidelity suspect(s), $W evidence error(s), $R unreferenced raw file(s)" && [ "$(printf '%s\n' "$OUT" | grep -c '^- article has no Raw field$')" -eq "$W" ] && [ "$(find docs/wiki -mindepth 2 -name '*.md' | wc -l)" -eq "$W" ] && [ "$(find docs/raw -name '*.md' | wc -l)" -eq "$R" ] && echo "MIGRATION-OK W=$W R=$R"</automated>
    <human-check>Before approving the follow-on deletion, the operator skims docs/wiki/index.md and opens one migrated article in an IDE preview or on GitHub, to confirm the diagram images render from the new location.</human-check>
  </verify>
  <done>verify_migration.py passes in full mode, with W=20 and R=51 unless drift was recorded. All
three negative controls failed as designed and then recovered. The skill's lint reports exactly
0 / W / R, and every evidence error is the no-Raw baseline. No original under docs/ changed. The
commit holds only docs/raw/, docs/wiki/ and the TSV.</done>
</task>

<task type="auto">
  <name>Task 3: Point agents at the docs/ wiki root and assemble the follow-on handoff</name>
  <files>.claude/CLAUDE.md</files>
  <action>
First invoke the `mattpocock-skills:writing-for-agents` skill. This is the user's standing
preference before editing any CLAUDE.md.

Then add one short hand-written section to .claude/CLAUDE.md. Place it after the
`## Local Development Server` section and before `## GSD Execution Directives`, outside every
`<!-- GSD:... -->` marker pair: GSD regenerates the contents of those markers, and the skills block
will list the new skill on its own. The section must state:
- The vendored karpathy-llm-wiki skill is rooted at `docs/`. Its raw/ is `docs/raw/` and its wiki/
  is `docs/wiki/`. Never create raw/ or wiki/ at the repository root.
- Its lint runs as `python3 .claude/skills/karpathy-llm-wiki/scripts/check_evidence.py docs`.
- Until a follow-on cleanup deletes the originals, the files under docs/ (top-level *.md,
  learning/, history/, incidents/, plans/) stay authoritative. Edit those, not the copies. The
  follow-on regenerates the copies with this task's migrate_docs.py before deleting.
- The migrated wiki articles carry no Raw links by design, so one "article has no Raw field" error
  per migrated article is the expected lint baseline, not a regression. Do not "fix" it by linking
  raws that were never compiled.
Keep it to a handful of lines, following the loaded skill's guidance. This is an addition only:
change no existing line.

Assemble the follow-on referrer list for the SUMMARY. It is a list, not a fix; do not edit any of
these files. Run `rg -n -F` for each old path form (`docs/ARCHITECTURE.md`, `docs/AUTH_FLOWS.md`,
`docs/CODE_STYLE.md`, `docs/DIAGRAM_CONVENTIONS.md`, `docs/INFRA_ARCHITECTURE.md`,
`docs/INFRA_RUNBOOK.md`, `docs/LOCAL_DEV.md`, `docs/SESSION_LESSONS.md`,
`docs/MOCKUP_FEATURE_GAP.md`, `docs/learning/`, `docs/history/`, `docs/incidents/`,
`docs/plans/backend-modernization`), plus the bare `../X.md` forms that other docs use. Always pass
explicit roots: README.md .claude/CLAUDE.md src scripts .github k8s docker infra build.gradle
docs, with `-g '!docs/raw/**' -g '!docs/wiki/**'`. Never run a bare rg from the repo root (it
would open .env files). Leave .planning/ out: it is a historical record. Record per-file hit
counts. The out-of-scope config-comment audit is NOT this: do not assess or trim any comment.

Re-run verify_migration.py after the edit. It must still pass. Commit only .claude/CLAUDE.md, by
explicit path, with a message like `docs(260927-ryo): point agents at the docs/ wiki root`. Do not
push. The same pre-commit memory-pressure rule applies.
  </action>
  <verify>
    <automated>cd /home/andre/dev/kanban-board-backend && rg -q -F 'docs/raw/' .claude/CLAUDE.md && rg -q -F 'check_evidence.py docs' .claude/CLAUDE.md && D=$(git diff -U0 HEAD -- .claude/CLAUDE.md) && [ "$(printf '%s\n' "$D" | grep -v '^---' | grep -c '^-')" -eq 0 ] && [ "$(printf '%s\n' "$D" | grep -c '^+[^+]')" -gt 0 ] && python3 .planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/verify_migration.py && echo POINTER-OK   # run before committing: it compares against HEAD</automated>
  </verify>
  <done>CLAUDE.md gains a pure-addition section outside the GSD markers that names the docs/ root,
the lint command, the originals-authoritative rule and the expected lint baseline. The referrer
list is gathered with explicit search roots. The verifier still passes. The commit contains only
.claude/CLAUDE.md.</done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| upstream GitHub repo → .claude/skills/ | Third-party content enters the repo: agent instructions (SKILL.md) auto-loaded into future sessions, and executable Python (check_evidence.py) |
| migration scripts → docs/ tree | Scripts with write access run next to the originals, which must stay untouched |
| local commit → origin/main push → deploy.yml → cluster | Paths outside deploy.yml's paths-ignore trigger an image build, a Flux image bump and a rollout to production and nonprod |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-ryo-SC | Tampering | vendored skill files | medium | mitigate | Fetch by pinned commit SHA eafcc77… via `gh api`. Task 1's verify compares `git hash-object` of all 7 files to the upstream tree's blob SHAs and asserts no extra files. No npm installer runs (deprecated add-skill rejected). |
| T-ryo-01 | Elevation of Privilege | SKILL.md as future agent instructions (prompt-injection surface) | medium | mitigate | Content reviewed at planning time: it writes only raw/ and wiki/, and runs only its own read-only script. The hash pin blocks silent upstream drift, so any upgrade must be a new, reviewed commit. The CLAUDE.md pointer constrains where it writes (docs/ root). |
| T-ryo-02 | Tampering | check_evidence.py execution | low | mitigate | Task 1's verify greps it for network, subprocess and file-write primitives and expects none. Its docstring declares it report-only, and the planning-time read confirmed that. |
| T-ryo-03 | Tampering | migrate_docs.py output integrity (mis-rewritten links, altered text) | medium | mitigate | An independent verify_migration.py checks faithfulness modulo link targets, position-paired link equivalence through the map, existence on disk, and header shape. Three negative controls in Task 2 prove it fails on each corruption class. |
| T-ryo-04 | Repudiation | originals under docs/ | medium | mitigate | Scripts open sources read-only. Verifier check (6) asserts working-tree bytes equal HEAD and that no commit subject containing 260927-ryo touches a source path. Staging is by explicit path only. |
| T-ryo-05 | Information Disclosure | duplicated incident/history content (VPS id, IPs) | low | accept | The content is already committed in this same repo, so the copy adds no new exposure. The pre-commit gitleaks staged-diff scan covers the copies, and .gitleaks.toml has only rule/match-scoped allowlists (verified), so copies are scanned exactly as the originals were. |
| T-ryo-06 | Denial of Service | push → deploy.yml → Flux rollout during D-08's window | medium | mitigate | The executor never pushes. SUMMARY states that LICENSE and check_evidence.py fall outside paths-ignore, that a push rebuilds the app image and rolls out both environments, and that D-08's window ends 2026-09-28T10:44:46Z, so the operator can choose push timing. No CI workflow is edited (operator constraint). |
| T-ryo-07 | Information Disclosure | credential-shaped strings in new files | low | mitigate | The pre-commit gitleaks scan runs on every commit. `--no-verify` is only used with fresh, single-commit operator authorization after gitleaks has passed, and never to bypass a finding. |
</threat_model>

<verification>
- Task 1 verify: blob-hash parity with upstream, exact 7-file skill directory, static scan clean,
  tracer slice verified, skill lint reports 0/1/2.
- Task 2 verify: full verifier pass (completeness, layout, headers, faithfulness, link
  equivalence and existence, originals untouched, index and log), skill lint reports exactly
  0 / W / R with only no-Raw errors, and all three negative controls observed failing and then
  recovering.
- Task 3 verify: pure-addition CLAUDE.md diff containing the root and lint pointers, and the
  verifier still green.
- Across all commits: `git log --name-only --format= <first-task-commit>^..HEAD` lists no path
  under docs/ outside docs/raw/ and docs/wiki/.
</verification>

<success_criteria>
The skill is installed per-repo and hash-verified. The 20 wiki articles and 51 raw sources exist
(or counts adjusted for recorded drift). Every relative link inside docs/wiki/ and docs/raw/
resolves to the equivalent of its original target. index.md and log.md follow the skill's formats.
The skill's own lint runs against the docs/ root with the documented baseline. The originals are
unchanged. Nothing is pushed.
</success_criteria>

<output>
Create `.planning/quick/260927-ryo-onboard-the-karpathy-llm-wiki-skill-gith/260927-ryo-SUMMARY.md`. It must include:
- The three planning-time scope findings from the objective: the four unlisted docs and where each
  went, the deprecated installer, and the deploy trigger with D-08's window end. Include any drift
  rows added at execution.
- The verifier's final count line, check_evidence.py's final summary line, and the three
  negative-control failure lines.
- A recommended follow-on task. First, re-run migrate_docs.py and verify_migration.py, because
  13-10 will likely have edited INFRA_RUNBOOK.md, INFRA_ARCHITECTURE.md and docs/history/ in the
  meantime. Then delete the originals: the 9 top-level docs, docs/learning/, docs/history/,
  docs/incidents/ and docs/plans/backend-modernization/. Keep docs/diagrams/ and docs/demo/. Then
  repoint the referrers, using the per-file list gathered in Task 3. Then decide whether the
  quick-task scripts are deleted.
- The raw compile backlog: R unreferenced raw sources, ready for a compile pass that turns the
  wiki's Raw fields into real links. This is out of scope here.
- A note that migrated content was copied as-is, and that staleness inside it was not assessed.
- Any `--no-verify` use: each one with the operator's in-session authorization, and the gitleaks
  result of the same staged diff.
- A note that nothing was pushed, and the push-timing consequence (T-ryo-06).
Do not write STATE.md; the orchestrator owns it.
</output>

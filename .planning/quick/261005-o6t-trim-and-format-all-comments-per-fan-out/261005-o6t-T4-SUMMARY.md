---
phase: quick-261005-o6t
plan: 01
task: T4
status: complete-with-caveats
plan_head_before: 72bb681ab915405c015612653901a5967af06225
plan_head_after: 1418e297fa4f8dec72d0395a8d3f57768bd3e383
commits: 4
---

# Quick task 261005-o6t, T4: non-Java comment pass

Comment-only rewrite of the non-Java in-scope files (k8s, infra, CI, hooks, scripts, config, SQL docs),
in four commits with the production-restarting edits isolated in the last one. Not committed: this file.

## Commits (base 72bb681, worktree branch worktree-agent-a62542adb88594fc9)

| # | Hash | Content |
|---|---|---|
| a | a554b79 | 50 files: k8s (incl. gotk header, overlays, Flux kustomizations), infra, Dockerfile, docker-compose.yml |
| b | 06a19c1 | .github (7), .githooks/pre-commit, scripts (12), docs/incidents probe.sh, docs/plans/*.sql (3) |
| c | 536a8ff | build.gradle, application*.properties, .gitleaks.toml, .env*.example, .dockerignore, .gitignore |
| d | **1418e29** (full 1418e297fa4f8dec72d0395a8d3f57768bd3e383) | **PRODUCTION-RESTARTING.** Postgres init scripts 01/02, Alloy `configMap.content` `//` lines, Traefik `valuesContent` `#` lines |

Not edited: Flyway `V*.sql` (verified byte-identical), `.gitattributes`, `settings.gradle`,
`docs/diagrams/render-manifest.tsv` (no gated ids or structure violations), `.claude/`, `.agents/`,
`docs/raw`, `docs/wiki`, `.planning/` (other than this file).

## Rollout commit record (d)

- Default: **revert 1418e29 unless the operator keeps it.** Reverting is clean (it touches only these 4 files).
- Render diff vs comment-pass-start, three roots differ, every other root is byte-identical:
  - `k8s/data/postgres`: ConfigMap renamed `postgres-init-62tc4bg5h4` -> `postgres-init-68f97kd279`
    (data differs only in comment lines) and the StatefulSet's volume reference follows. Diff with comment lines
    filtered out is exactly those two `name:` lines.
  - `k8s/monitoring/controllers`: Alloy HelmRelease `configMap.content` (2 comment blocks).
  - `k8s/platform`: Traefik HelmChartConfig `valuesContent` (2 comment blocks).
- Consequence on main: restart of the single shared Postgres StatefulSet (serves BOTH prod and nonprod),
  Alloy redeploy, Traefik redeploy (blip on the public edge). The lint exempts the init scripts
  (ROLLOUT_GATED), so reverting never turns the gate red.
- Commits a, b, c rendered byte-identical for all 12 roots before (d) was made (run output below).

## Checks run, with real output

- `python3 scripts/verify-comments.py check <T4 paths>`: `OK: 95 files, 482 comment blocks`, exit 0.
  Caveat below: this is blind past line ~40 of `scripts/verify-postgres-init-quoting.sh`.
- `equiv --base comment-pass-start` over T4 paths (allow-listing only alloy.yaml and helmchartconfig.yaml):
  every file `equivalent` EXCEPT `scripts/verify-postgres-init-quoting.sh`: `cannot verify: shell lexer ended
  inside a quote or heredoc` and `code differs`. Cause: a tool limitation, see "Unverified / tool findings".
  Manual proof for that file: non-comment, non-blank lines compared via `git show` vs working tree: `EQUAL 175 175`.
  Also manually EQUAL for application.properties (52), application-test.properties (40), init/01 (23), init/02 (13).
- Render identity (kubectl v1.36.4 `kustomize` per root, base extracted with `git archive comment-pass-start k8s`):
  after a-c: `roots=12 differing=0`. After d: `roots=12 differing=3` (the three above).
- `bash scripts/verify-k8s-manifests.sh`: `verify-k8s-manifests: all 12 kustomization root(s) valid` (after a-c and after d).
- `python3 scripts/verify-k8s-invariants-selftest.py`: `selftest OK -- I1-I10 each fire on an engineered violation`.
- `python3 scripts/verify-k8s-invariants.py --no-provisional`: `invariants OK -- 12 kustomization root(s) checked; ...`
  (I4 MEASURED-within-25-lines holds; every MEASURED keyword kept).
- `python3 scripts/verify-public-dashboards-selftest.py`: `SELFTEST OK`; `verify-public-dashboards.py`: `invariants OK -- k8s: 3 dashboard(s) checked`.
- `bash -n` over `git ls-files -- '*.sh' .githooks/pre-commit ':!.dev'`: `bash -n OK for 13 shell files`.
- shellcheck: SC-code multiset identical to comment-pass-start for all 8 touched shell scripts
  (`SAME as base`); pre-existing findings (SC1073 on directive text, SC2155, SC2181, SC2329) are untouched.
- Migrations: `git diff --quiet comment-pass-start -- src/main/resources/db/migration` exit 0 (byte-identical).
- gitleaks 8.30.1 `dir -c .gitleaks.toml --exit-code 2` per changed file: `gitleaks failures: 0` for 50 (a), 23 (b),
  9 (c) and 4 (d) files.
- No Gradle was started (no formatter targets non-Java files).

## Comment lines before -> after (stats over T4 paths only, `stats --ref comment-pass-start` vs working tree)

| family | files | comment lines | % of lines | blocks > 8 prose lines | gated ids | suspects |
|---|---|---|---|---|---|---|
| gradle | 2 | 440 -> 364 | 56.7 -> 52.0 | 14 -> 11 | 44 -> 0 | 23 -> 1 |
| yaml (k8s, infra) | 52 | 625 -> 392 | 27.2 -> 19.0 | 19 -> 4 | 125 -> 0 | 141 -> 4 |
| yml (workflows, dependabot, loadtest, compose) | 10 | 664 -> 423 | 44.7 -> 34.0 | 21 -> 11 | 106 -> 0 | 38 -> 1 |
| sh (incl. pre-commit, init scripts) | 10 | 498 -> 385 | 35.4 -> 30.0 | 12 -> 10 | 23 -> 0 (+5 exempt -> 0) | 23 -> 4 |
| properties | 2 | 306 -> 226 | 72.7 -> 66.3 | 8 -> 5 | 31 -> 0 | 4 -> 2 |
| sql (9 frozen, 4 docs/scripts) | 13 | 229 -> 142 | 60.6 -> 48.8 | 4 -> 4 | 21 -> 0 (14 exempt, frozen) | 0 |
| other (py, toml, dotenv, ignore, Dockerfile, conf, service, tsv) | 15 | 534 -> 511 | 26.6 -> 25.7 | 9 -> 9 | 50 -> 0 | 29 -> 7 |
| TOTAL T4 | 104 | 3296 -> 2443 | 37.6 -> 30.9 | 87 -> 54 | 400 -> 0 | 258 -> 19 |

"Blocks > 8 prose lines" stays high because decision records are kept long on purpose, segregated under
`Decisions:`/`Known holes:` markers (R3 only limits prose before the first marker). The 14 frozen-migration id hits
are exempt and untouched. The `.githooks/pre-commit`, workflows and `.githooks` file list are included in the
sh/yml rows.

## `.gitleaks.toml` evidence kept

Every allowlist entry keeps its finding, why it is false, and the commit/file evidence (12 of 13 baseline findings are
`curl -u ...${{ secrets.* }}`; the synthetic `AKIATESTFAKEKEY23456` canary; the deliberately NOT allowlisted real
2025-06-05 local-dev password, application.properties:10 commit 5121740f61, suppressed only by the committed
`.gitleaks-baseline.json`). The bare `260816-hn1-MEASUREMENTS.md` is now the full path
`.planning/quick/260816-hn1-wire-up-secret-scanning-gitleaks-truffle/260816-hn1-MEASUREMENTS.md` (resolves; also cited
for -PLAN.md). `description =` TOML values untouched. The same MEASUREMENTS path is cited from `.githooks/pre-commit`,
`secret-scan.yml` and `.gitignore`, because the observations (gitleaks dir ignores .gitignore, worktree commondir,
MSYS path rewriting, --redact guarantee) exist nowhere else. Other full-path citations kept where the evidence
exists nowhere else: build.gradle (3 quick-task MEASUREMENTS/RESEARCH files), `.dockerignore` (260811-nh1-PLAN.md),
application.properties (`.planning/debug/resolved/admin-reset-500-nonprod.md`).

## Comments kept because something points at them

- `infra/vm/k3s-host-firewall.sh` header: all five points docs/INFRA_RUNBOOK.md and INFRA_ARCHITECTURE.md rely on
  (why position 1, why mangle, dead-man switch, IPv4-only, what is deliberately not checked) kept as a `Decisions:` + `Known holes:` record.
- `infra/vm/k3s/config.yaml` servicelb record (infra/vm/README.md points at it); `infra/vm/sshd/kanban-ci-tunnel.conf` header.
- `k8s/monitoring/configs/ingressroute.yaml` and `k8s/overlays/prod/ingressroute.yaml` dated rate-limit derivations (INFRA_RUNBOOK.md L236).
- `scripts/verify-public-dashboards.py` module docstring, failures 1-3 verbatim in substance (docs/learning/11 points at "failure 3").
- `docs/plans/backend-modernization/02-...ddl.sql` EC2->Netcup annotation (docs/history/2026-08-17 decommission record
  points at it) and `04-...ddl.sql` pre-flight NULL-count guard (`UserEntity.java:45` points at it).
- build.gradle: forkEvery "catastrophic rather than merely slower" wording and the measured fork numbers
  (docs/learning/08, docs/LOCAL_DEV.md quote them); `application.properties` Hikari/keepalive records (docs/learning/10).
- Functional lines untouched: Flux `$imagepolicy` markers (both overlays), `# shellcheck disable=` directives,
  shebangs, `planner-discipline-allow: DB_JDBC_PARAMS` (application.properties:~50, read by the GSD planner gate),
  every MEASURED keyword (I4).

## Deleted comments that carried a TODO or "revisit" (for the orchestrator to capture)

No `TODO`/`FIXME` existed in T4's code files (the only hits are in markdown docs and frozen V5). Plain-prose "revisit":
- `.github/workflows/deploy.yml` (Buildx step): "revisit only if a concrete need for arm64 surfaces later". Deleted, no tracked item.
- `src/main/resources/application.properties` Kafka producer bounds: "Revisit these bounds once a real production
  broker lands (KAFKA-V2-01)". Deleted; KAFKA-V2-01 is not in any file I could grep under `.planning/*.md`.
- Converted, not deleted: build.gradle "pick a failBuildOnCVSS rung once a real baseline exists" is now
  `TODO: .planning/todos/pending/2026-08-13-ratchet-failbuildoncvss-after-a-real-dependency-check-baseline.md - ...`
  (the pending todo exists).
- Falsifiers kept as written ("Falsifiable: ... revisit" lines in properties, init scripts, k8s memory records).

## Deviations from the plan

1. **[Rule 3] Keyword-count equiv forced three kept "PROVISIONAL" mentions.** `equiv` compares per-line first
   MEASURED/PROVISIONAL keyword counts for every comment, not only memory-cap comments. The prod and monitoring
   rate-limit comments mentioned "the earlier PROVISIONAL placeholder", and `verify-k8s-invariants.py`'s own
   docstring wraps keywords; I kept those phrases/wraps so counts match instead of using `--allow`. This keeps one
   clause of history per file against the rubric's deletion preference.
2. **MEASURED dates:** no date was invented. build.gradle fork measurements, which carried only a quick-task id, are now undated
   (the 2026-08-12 JaCoCo checkpoint date and 2026-08-26/27 dates in the originals are kept).
3. Plan said the lint's own R2 treats a section header line as paragraph line 1, so header + 1-line summary + blank
   was used for headed blocks (e.g. `# === graceful shutdown ===` in properties).

## Unverified / tool findings (for T5 or the orchestrator)

- **`scripts/verify-comments.py` shell lexer does not understand ANSI-C quoting `$'...\'...'`** (line 41-ish of
  `scripts/verify-postgres-init-quoting.sh`: `HOSTILE_LITERAL_PUNCTUATION=$'a\'b"c\\d$e '`). Consequences: `equiv`
  cannot pass that file (warning `shell lexer ended inside a quote or heredoc` + false `code differs`), and `check`
  treats every comment after that line as quoted content, so R1-R4 are blind there. I verified manually: no gated id
  patterns remain in the file (grep), and non-comment lines are identical. This also fails with the BASE copy
  (pre-existing, not caused by my edit). T5 must fix the lexer or allow-list the file; as is, the final
  `equiv --allow ...` gate cannot exit 0.
- `equiv`'s keyword check counts first keyword per comment line, so re-wrapping a comment can change the count
  (hit in `verify-k8s-invariants.py`; fixed by re-wrapping). Brittle against future edits.
- gitleaks: `gitleaks git --staged` and the hook's docker scan were not run (the harness refuses `gitleaks git` and
  `--no-verify` was authorised); only per-file `gitleaks dir` was run over the changed files.
- Windows behaviour of anything shell-related: not verifiable here.
- No live Flux/helm render of the HelmRelease values (alloy, traefik) was possible; only kustomize render diffs.
- Not verified at runtime: that `docker/postgres` still sources the edited init scripts (comment-only; `bash -n` and
  non-comment-line equality only). The PasswordEncoderStrengthTest (reads application.properties minus `#` lines) was
  not run (no Gradle); all edited lines still start with `#`, non-comment lines are equal.
- Stale content noticed but NOT changed (out of scope, comment-only mandate): `init/01-...sh` recovery advice still says
  `docker compose down -v` (Compose is decommissioned; the k3s analogue is deleting the PVC); `.env.prod.example`,
  `.env.nonprod.example`, `docker-compose` comments still describe the Compose/Caddy era; docs/learning line anchors
  into build.gradle/secret-scan.yml/deploy.yml (e.g. `#L157-L162`) are now off; docs/learning/10:381 says the
  Dockerfile header records INFRA-07 reasons (the Dockerfile only ever had two stage-label comments, now removed).

## Rename suggestions

None (comment-only; no names were load-bearing).

## Self-Check

- Created file present: `.planning/quick/261005-o6t-trim-and-format-all-comments-per-fan-out/261005-o6t-T4-SUMMARY.md` (uncommitted).
- Commits a554b79, 06a19c1, 536a8ff, 1418e29 are ancestors of HEAD (`git rev-list --count 72bb681..HEAD` = 4).
- Self-check status: PASSED for the files and commits; the plan's `equiv exit 0` criterion is NOT met for one file (tool limit, above).

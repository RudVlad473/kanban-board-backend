# Automated Avro schema registration — Plan 09-03 (2026-08-19)

Automates the last hand-run step in this project's deploy (CI-05): registering the application's
14 Avro schemas against both the production and nonprod Confluent-compatible Schema Registries on
every push to `master`, reusing the existing `AvroSchemaRegistrar`/`PropertiesLauncher`
one-off-container mechanism verbatim (`src/main/java/com/vrudenko/kanban_board/config/AvroSchemaRegistrar.java`
is unmodified by this plan — no source file under `src/` was touched, confirmed by
`git diff --name-only -- src/` returning empty across this plan's commits). `AvroSchemaRegistrar`
remains the only code in this repository that writes schemas to a registry (SCHEMA-01); the deployed
application itself is configured `spring.kafka.producer.properties.auto.register.schemas=false`, so
it can only look a schema up, never register one.

## Task 1 checkpoint — decision and rationale

The human operator selected **option-a**: `register-schemas-production` runs as its own job
immediately *after* `deploy-to-netcup`, rather than restructuring `deploy-to-netcup` itself to gate
`up -d` on registration succeeding first (option-b) or deferring the choice to Phase 10 (option-c).
Rationale, as given: option-a is strictly better than the status quo (the unregistered window
shrinks from "until an operator remembers the runbook" to "this job's own startup latency"), makes
zero behaviour changes to a live, proven production deploy script, and avoids introducing a new
production failure mode (a schema-registry outage blocking an otherwise-successful deploy) — all
while an incompatible schema still reddens the run via the same exit-code chain nonprod's own
registration relies on. This matches `09-RESEARCH.md`'s own recommendation and the non-regressing
reading of CI-05.

This creates a deliberate, *recorded* asymmetry between the two environments, not an oversight:

- **Nonprod** gets CI-05's "registration completes before that environment's app serves traffic"
  clause literally. Nonprod was being automated from scratch with no existing traffic to protect,
  so there was no reason to accept a window here — registration is a step *inside*
  `deploy-to-nonprod`'s own SSH script, positioned between `up -d redpanda-nonprod` and
  `up -d app-nonprod`. `app-nonprod` does not exist as a process until registration has already
  succeeded, and a registration failure aborts the script before that line is ever reached.
- **Production** keeps its existing brief window between `up -d` and registration — this is not a
  gap this plan introduces, it is the status quo (a hand-run post-deploy step since v1.2 Phase 5,
  see "Manual deploy — Plan 05-04 Task 1" above) now automated rather than left to an operator's
  memory. `register-schemas-production` is its own job, `needs: [ deploy-to-netcup,
  build-and-push-docker-image ]`, `environment: production`, with no `needs:` edge to any nonprod
  job — it neither gates nor is gated by the nonprod deploy path.

## The two invocations, verbatim as they appear in CI

**Nonprod** — a step inside `deploy-to-nonprod`'s existing `appleboy/ssh-action` script
(`.github/workflows/deploy.yml`), between the broker-up and app-up lines:
```
docker compose --env-file ./.env.nonprod -f docker-compose.nonprod.yml --profile nonprod run --rm --entrypoint java app-nonprod \
  -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
  -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda-nonprod:8081
```

**Production** — the sole step of the new `register-schemas-production` job:
```
docker compose --env-file ./.env.prod -f docker-compose.prod.yml run --rm --entrypoint java app \
  -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
  -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda:8081
```

Both are the identical `PropertiesLauncher` technique already proven by hand (see the superseded
manual steps above) — CI now runs them instead of an operator. `IMAGE_TAG` is exported from
`needs.build-and-push-docker-image.outputs.image_tag` in both jobs before either invocation runs,
so the one-off container always resolves the same image tag the surrounding deploy just pulled;
neither invocation re-derives the tag or substitutes a floating one (this project publishes no
`latest` tag on Docker Hub).

## The exit-code chain that makes an incompatible schema redden the run

Five links, each necessary: `AvroSchemaRegistrar.registerThenSetCompatibility` throws
`IllegalStateException` on an unrecoverable registry error (an incompatible schema, or a
genuinely unreachable registry) → the JVM exits non-zero → `docker compose run --rm` propagates
the container's exit code → an explicit `set -e` as the first line of the script aborts the
remaining commands → `appleboy/ssh-action`'s shell process exits non-zero, failing the step.

**The fourth link was missing when this section was first written, and live verification is what
caught it (2026-08-19).** `appleboy/ssh-action` has no fail-fast behavior of its own — it runs a
multi-line `script:` under a plain shell, and a failing command mid-script does not stop later
lines from running unless the script itself opts in. A live red-path test against
`deploy-to-nonprod` (deliberately pointing the registrar at an unreachable host) showed the
registrar correctly throwing `IllegalStateException` and `docker compose run` correctly exiting 1
at the OS level (verified directly over SSH), yet the script continued past it and `up -d
app-nonprod` still ran, with the job reporting `success`. A first fix attempt added
`script_stop: true` to the action's `with:` block — that input does not exist on
`appleboy/ssh-action@v1.2.5` at all, so GitHub Actions silently ignored it (unknown `with:` keys
never error) and the bug reproduced identically on retest. The real fix is `set -e` as the
literal first line of each script's shell text, applied to all three `appleboy/ssh-action` steps
in this file. Neither invocation is wrapped in `continue-on-error`, `set +e`, or any other
construct that would swallow that exit code — verified mechanically (neither job block contains
those literal strings) and now also verified live (see "Live verification" below).

## Registry URL isolation

Exactly two distinct registry URLs exist in `deploy.yml`: `http://redpanda-nonprod:8081` (appears
once, only inside `deploy-to-nonprod`'s script) and `http://redpanda:8081` (appears once, only
inside `register-schemas-production`'s script). Neither broker publishes a host port on either
stack (`docker-compose.nonprod.yml` and `docker-compose.prod.yml` both have no `ports:` key for
their `redpanda*` service), so the registrar is reachable only from inside each stack's own
Compose network — a mis-scoped job cannot reach the other environment's registry even with the
wrong URL, because there is no routable path to it at all.

## Idempotency and cross-broker independence — measured live (2026-08-19)

`AvroSchemaRegistrar` is idempotent by construction (`registerOne`'s Javadoc): registering an
unchanged schema returns the existing schema id, and re-setting an unchanged compatibility level
is itself a no-op write. Because subjects are keyed by Avro record full name under
`RecordNameStrategy`, per broker, a nonprod registration cannot mutate production's compatibility
history — `GET /subjects/<name>/versions` against each broker reads each one's own independent
version list. These are structural properties of the reused mechanism, already relied upon by both
`registerSchemas`'s own repeated Gradle-task invocations and every test class sharing
`AbstractKafkaContainerTest`'s harness — this plan does not change `AvroSchemaRegistrar` itself, so
it does not change this behavior.

**Confirmed against the live CI-driven path.** Both brokers' `rpk registry schema list` output
stayed byte-identical (14 subjects, all version 1, same IDs) across a baseline capture, a real
push, and a `gh run rerun` of the same commit — proving idempotency. `register-schemas-production`
and `deploy-to-netcup` succeeded fully independently across three separate live runs that
deliberately broke nonprod's registration only — proving cross-broker independence: production's
path never failed, slowed, or was gated by nonprod's state. See "Live verification" below for the
full run reference list.

## Consolidated job graph — every job in the final pipeline

Both vertical paths share exactly one node, consumed read-only: `build-and-push-docker-image`
(one build, two Docker Hub tags, unchanged since Plan 09-01). No `needs:` edge crosses between the
two paths anywhere else.

| Job | Environment | Path |
|---|---|---|
| `setup` | none (no secrets) | shared |
| `run-tests` | none (no secrets) | shared |
| `build-and-push-docker-image` | `production` | shared (the one node both paths consume) |
| `flyway-verify` | `production` | production |
| `deploy-to-netcup` | `production` | production |
| `register-schemas-production` | `production` | production |
| `cleanup-old-images` | `production` | production |
| `cleanup-unused-image` | `production` | production |
| `flyway-verify-nonprod` | `staging` | nonprod |
| `deploy-to-nonprod` | `staging` | nonprod (registration is a step inside this job, not a separate job — see Task 1 rationale above) |
| `health-check-nonprod` | `staging` | nonprod |
| `cleanup-old-images-nonprod` | `staging` | nonprod |
| `cleanup-unused-image-nonprod` | `staging` | nonprod |

```
setup ─┬─> run-tests ─> build-and-push-docker-image ─┬─> flyway-verify ─> deploy-to-netcup ─┬─> register-schemas-production
       │                                             │                                      ├─> cleanup-old-images
       │                                             │                                      └─> cleanup-unused-image
       │                                             └─> flyway-verify-nonprod ─> deploy-to-nonprod (registers schemas internally) ─> health-check-nonprod ─┬─> cleanup-old-images-nonprod
       │                                                                                                                                                    └─> cleanup-unused-image-nonprod
       └──────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
```

No `register-schemas-nonprod` job id exists — by design, per Task 1's rationale, nonprod's
registration had to be a step inside `deploy-to-nonprod` itself to precede `up -d app-nonprod`.

## Live verification (2026-08-19) — completed after merge to master, including a mid-verification bug fix

This plan's file-level work (`.github/workflows/deploy.yml`, this section) was authored and
statically verified inside an isolated git worktree, the same execution context Plans 09-01 and
09-02 both documented above. Once the orchestrator merged the worktree's commits to `master`, the
human operator drove each remaining step directly:

1. **Green path:** confirmed on run `32241094339` (and its `gh run rerun`) — both jobs logged
   `Registered 14 Avro schemas against <url>` against the correct registry, and `rpk registry
   schema list` on both brokers confirmed 14 subjects, all version 1.
2. **Red path — two failed attempts uncovered a real defect before the third succeeded.**
   Pointing `deploy-to-nonprod`'s registrar invocation at an unreachable host (commit `449614e`,
   run `32242756450`) showed the registrar correctly throwing `IllegalStateException`, but
   `app-nonprod` was recreated and started anyway, and the job reported `success` — see "The
   exit-code chain" above for the root cause (`appleboy/ssh-action` has no fail-fast behavior of
   its own) and the two-attempt fix history (`script_stop: true` was a no-op; `set -e`, commit
   `9eca655`, was the real fix). A third red-path attempt (run `32246183734`) then correctly
   failed: `deploy-to-nonprod` conclusion `failure`, `##[error]Process completed with exit code 1`,
   `health-check-nonprod`/`cleanup-old-images-nonprod` correctly stayed skipped,
   `cleanup-unused-image-nonprod` correctly fired its D-06 cleanup, and `docker inspect
   kanban-nonprod-app`'s `StartedAt` timestamp was confirmed byte-identical before and after the
   run — the container was genuinely never touched. URL reverted (commit `89fde91`) and
   re-verified green.
3. **Idempotency:** confirmed — see "Idempotency and cross-broker independence" above.
4. **Cross-broker independence:** confirmed — `register-schemas-production` succeeded
   independently across all three nonprod-targeting red-path runs.

Every acceptance criterion in this plan's Tasks 1–3 is now live-verified, not just statically
checked. See this plan's `09-03-SUMMARY.md` for the full commit/run reference list. A separate,
unrelated flaky test (`ResetServiceE2ETest`) surfaced during the final green-restoration run,
confirmed as flakiness (an identical-commit re-run passed clean) and filed as its own todo.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._

# Phase 9: Nonprod Continuous Deploy & Scoped CI Credentials - Pattern Map

**Mapped:** 2026-08-18
**Files analyzed:** 2 (1 primary code file with ~7 new jobs inside it, 1 doc file)
**Analogs found:** 2 / 2 — this phase is a same-file, in-place analog copy (every new job's analog lives in the exact file being modified)

## File Classification

| New/Modified File | Role | Data Flow | Closest Analog | Match Quality |
|--------------------|------|-----------|-----------------|----------------|
| `.github/workflows/deploy.yml` — new job `flyway-verify-nonprod` | CI job (migration verification) | batch / request-response (one-shot container run) | `flyway-verify` (same file, lines 102-141) | exact |
| `.github/workflows/deploy.yml` — new job `deploy-to-nonprod` | CI job (deploy orchestration) | file-I/O (scp) + event-driven (ssh exec) | `deploy-to-netcup` (same file, lines 152-208) | exact |
| `.github/workflows/deploy.yml` — new job `health-check-nonprod` | CI job (readiness probe) | request-response (polling) | none in-repo (new pattern; nearest shape is any `run:` step with explicit HTTP status check, e.g. `cleanup-old-images`'s `curl -w "%{http_code}"` idiom, lines 247-256) | partial — pattern exists (status-code curl), full retry-loop job does not |
| `.github/workflows/deploy.yml` — new jobs `register-schemas-nonprod` / `register-schemas-production` | CI job (one-off container invocation over SSH) | event-driven (fire-once command) | `deploy-to-netcup`'s SSH-script step (lines 186-208), specifically its `appleboy/ssh-action` shape | role-match (deploy step reused as invocation template; command body sourced from `docs/INFRA_RUNBOOK.md` runbook text, not from any existing CI job) |
| `.github/workflows/deploy.yml` — new job `cleanup-old-images-nonprod` | CI job (Docker Hub tag sweep) | batch (list + delete loop) | `cleanup-old-images` (same file, lines 210-275) | exact |
| `.github/workflows/deploy.yml` — new job `cleanup-unused-image-nonprod` | CI job (single-digest delete on failure) | event-driven (failure-triggered) | `cleanup-unused-image` (same file, lines 278-301) | exact |
| `.github/workflows/deploy.yml` — `setup` job extension (second `base_image_name`-shaped output for nonprod repo) | config/utility (output computation) | transform | `setup` job's `create_environment_variables` step (lines 13-20) | exact |
| `docs/INFRA_RUNBOOK.md` — secrets table + nonprod CI section update | doc (config reference) | n/a | existing "Repository secrets" table (line 486 region) and nonprod bring-up section (lines 977-1178) | exact (doc-editing convention, not code) |

## Pattern Assignments

### `flyway-verify-nonprod` (CI job, batch/request-response)

**Analog:** `flyway-verify`, `.github/workflows/deploy.yml:102-141`

**Full pattern to copy (needs/if, guard step, migration step):**
```yaml
flyway-verify:
  needs: [ setup, run-tests ]
  runs-on: ubuntu-latest
  if: success()
  steps:
    - name: Checkout code
      uses: actions/checkout@v5

    - name: Guard against Neon's pooled (transaction-mode) endpoint
      env:
        DB_HOST: ${{ secrets.DB_HOST }}
      run: |
        if [[ "$DB_HOST" == *"-pooler"* ]]; then
          echo "::error::DB_HOST carries Neon's pooler marker (-pooler). ..."
          exit 1
        fi
        echo "Guard passed: configured endpoint is not the pooled endpoint."

    - name: Verify Flyway migrations apply cleanly (Neon direct endpoint)
      env:
        FLYWAY_URL: jdbc:postgresql://${{ secrets.DB_HOST }}:5432/${{ secrets.DB_NAME }}?sslmode=require
        FLYWAY_USER: ${{ secrets.DB_USER }}
        FLYWAY_PASSWORD: ${{ secrets.DB_PASS }}
      run: |
        docker run --rm \
          -e FLYWAY_URL -e FLYWAY_USER -e FLYWAY_PASSWORD \
          -v "${{ github.workspace }}/src/main/resources/db/migration:/flyway/sql:ro" \
          flyway/flyway:11.7.2 migrate
```

**Changes for `flyway-verify-nonprod`:**
- Add `environment: staging` (Pattern 1, CI-02) — this is the axis production's version does not have, since it is not yet environment-scoped (only the new nonprod job needs to be, per CONTEXT.md D-04's scoping rationale; do not retrofit `environment:` onto the untouched `flyway-verify`, out of this phase's file-scope discipline).
- The guard step and migration step are copied verbatim except the `secrets.*` names, which stay `DB_HOST`/`DB_NAME`/`DB_USER`/`DB_PASS` — but because the job now declares `environment: staging`, these resolve against the `staging` environment's *own* secret values (a distinct nonprod Neon branch), not production's, without renaming the secret keys themselves. Confirm with the planner whether nonprod's DB secrets are named identically (`DB_HOST` etc., scoped per-environment) or need a `_NONPROD` suffix — GitHub Environments allow same-named secrets to differ in value per environment, which is the simpler, less error-prone choice and matches this pattern's reuse of the exact guard/step names.

---

### `deploy-to-nonprod` (CI job, file-I/O + event-driven)

**Analog:** `deploy-to-netcup`, `.github/workflows/deploy.yml:152-208`

**Imports/job-header pattern (concurrency + needs, lines 152-167):**
```yaml
deploy-to-netcup:
  needs: [ build-and-push-docker-image, flyway-verify ]
  runs-on: ubuntu-latest
  if: success()
  concurrency:
    group: deploy-to-netcup-vm
    cancel-in-progress: false
```
For `deploy-to-nonprod`: `needs: [ build-and-push-docker-image, flyway-verify-nonprod ]`, `environment: staging`, `concurrency.group: deploy-to-nonprod-vm` (a distinct string — Pattern 2, Anti-Pattern warning against reusing `deploy-to-netcup-vm`).

**SCP step pattern (lines 172-184):**
```yaml
- name: Copy Compose manifest and Caddyfile to the VM
  uses: appleboy/scp-action@v1.0.0
  with:
    host: ${{ secrets.NETCUP_HOST }}
    username: ${{ secrets.NETCUP_DEPLOY_USER }}
    key: ${{ secrets.NETCUP_SSH_KEY }}
    fingerprint: ${{ secrets.NETCUP_HOST_FINGERPRINT }}
    source: "docker-compose.prod.yml,Caddyfile"
    target: "/opt/deploy/kanban-board-backend/"
```
For nonprod: `username`/`key` come from the new D-01 credential (e.g. `secrets.NETCUP_NONPROD_DEPLOY_USER` / `secrets.NETCUP_NONPROD_SSH_KEY`), `host`/`fingerprint` stay the same shared-host secrets (same physical VM, per Established Patterns note), `source: "docker-compose.nonprod.yml"` (no separate Caddyfile — confirm against Phase 8's actual nonprod bring-up; `docs/INFRA_RUNBOOK.md`'s nonprod section does not show a second Caddyfile transfer), `target: "/opt/deploy/kanban-board-nonprod/"`.

**SSH deploy-script step pattern (lines 186-208):**
```yaml
- name: Deploy via Docker Compose
  uses: appleboy/ssh-action@v1.2.5
  with:
    host: ${{ secrets.NETCUP_HOST }}
    username: ${{ secrets.NETCUP_DEPLOY_USER }}
    key: ${{ secrets.NETCUP_SSH_KEY }}
    fingerprint: ${{ secrets.NETCUP_HOST_FINGERPRINT }}
    script: |
      cd /opt/deploy/kanban-board-backend
      export IMAGE_TAG=${{ needs.build-and-push-docker-image.outputs.image_tag }}
      docker compose --env-file ./.env.prod -f docker-compose.prod.yml pull app
      docker compose --env-file ./.env.prod -f docker-compose.prod.yml up -d
```
For nonprod, per Anti-Pattern warning (`docker-compose.nonprod.yml` gates services behind `profiles: ["nonprod"]`) and the Architecture Patterns job-graph sketch:
```yaml
cd /opt/deploy/kanban-board-nonprod
export IMAGE_TAG=${{ needs.build-and-push-docker-image.outputs.image_tag }}
docker compose --env-file ./.env.nonprod -f docker-compose.nonprod.yml --profile nonprod pull app-nonprod
docker compose --env-file ./.env.nonprod -f docker-compose.nonprod.yml --profile nonprod up -d app-nonprod
```
Note the `--profile nonprod` flag on both `pull` and `up` — omitting it is the exact Anti-Pattern this phase's research calls out.

---

### `health-check-nonprod` (CI job, request-response polling) — new pattern, not a copy

**No existing job-shaped analog.** Nearest sub-pattern is the explicit HTTP-status-check idiom already used in `cleanup-old-images` (lines 247-256, `curl -s -o /tmp/tags.json -w "%{http_code}"` then compare) and in `flyway-verify`'s guard step (fail loudly with `::error::` + `exit 1`). Compose the retry loop from RESEARCH.md's Code Examples section directly:
```bash
HEALTH_URL="https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/actuator/health"
ATTEMPTS=12
SLEEP_SECONDS=5
for i in $(seq 1 "$ATTEMPTS"); do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$HEALTH_URL" || echo "000")
  if [ "$STATUS" = "200" ]; then
    echo "Nonprod healthy after $i attempt(s)."
    exit 0
  fi
  echo "Attempt $i/$ATTEMPTS: got HTTP $STATUS, retrying in ${SLEEP_SECONDS}s..."
  sleep "$SLEEP_SECONDS"
done
echo "::error::Nonprod health endpoint never returned 200 after $ATTEMPTS attempts ($((ATTEMPTS * SLEEP_SECONDS))s total)."
exit 1
```
Wrap as `needs: [ deploy-to-nonprod ]`, `environment: staging`, `runs-on: ubuntu-latest`, no external action — matches this repo's existing preference for inline bash over marketplace actions (Alternatives Considered table in RESEARCH.md).

---

### `register-schemas-nonprod` / `register-schemas-production` (CI job, event-driven one-off container)

**Analog for job/step shape (SSH-action usage):** `deploy-to-netcup`'s deploy step, `.github/workflows/deploy.yml:186-208` (same `appleboy/ssh-action@v1.2.5` wrapper, same `host`/`username`/`key`/`fingerprint` argument shape).

**Command body (not from any CI job — sourced from `docs/INFRA_RUNBOOK.md` manual runbook text, already proven live by hand):**
```bash
# nonprod (docs/INFRA_RUNBOOK.md ~lines 1018-1020)
docker compose -f docker-compose.nonprod.yml --env-file ./.env.nonprod --profile nonprod run --rm \
  --entrypoint java app-nonprod -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
  -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda-nonprod:8081

# production (docs/INFRA_RUNBOOK.md ~lines 163-165) — same shape, prod broker/creds
docker compose -f docker-compose.prod.yml --env-file ./.env.prod run --rm \
  --entrypoint java app -Dloader.main=com.vrudenko.kanban_board.config.AvroSchemaRegistrar \
  -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher http://redpanda:8081
```
`register-schemas-nonprod`: `needs: [ health-check-nonprod ]` (or `deploy-to-nonprod` — planner's explicit ordering decision, RESEARCH.md Open Question 2), `environment: staging`, uses the new D-01 nonprod SSH identity (D-03: same credential as `deploy-to-nonprod`).
`register-schemas-production`: `needs: [ deploy-to-netcup ]`, `environment: production`, uses the existing `NETCUP_SSH_KEY`/`NETCUP_DEPLOY_USER` (production's identity, unchanged).

---

### `cleanup-old-images-nonprod` (CI job, batch list+delete)

**Analog:** `cleanup-old-images`, `.github/workflows/deploy.yml:210-275` — copy verbatim.

**Core pattern (JWT login-token exchange, lines 229-235):**
```bash
HUB_TOKEN=$(curl -s -H "Content-Type: application/json" -X POST \
  -d "{\"username\": \"$DOCKERHUB_USER\", \"password\": \"${{ secrets.DOCKERHUB_TOKEN }}\"}" \
  "https://hub.docker.com/v2/users/login/" | jq -r .token)
if [ -z "$HUB_TOKEN" ] || [ "$HUB_TOKEN" = "null" ]; then
  echo "::error::Docker Hub login-token exchange failed -- no token in response. Cannot prune tags."
  exit 1
fi
```

**Paginated list pattern (lines 244-256) — MUST keep the `while [ -n "$NEXT_URL" ]` pagination loop (already fixed 2026-08-17 bug), not the earlier single-request version:**
```bash
TAGS=""
NEXT_URL="https://hub.docker.com/v2/repositories/${{ needs.setup.outputs.base_image_name }}/tags/?page_size=100"
while [ -n "$NEXT_URL" ] && [ "$NEXT_URL" != "null" ]; do
  TAGS_HTTP_STATUS=$(curl -s -o /tmp/tags.json -w "%{http_code}" \
    -H "Authorization: JWT ${HUB_TOKEN}" \
    "$NEXT_URL")
  if [ "$TAGS_HTTP_STATUS" != "200" ]; then
    echo "::error::Listing tags failed with HTTP $TAGS_HTTP_STATUS: $(cat /tmp/tags.json)"
    exit 1
  fi
  TAGS="$TAGS $(jq -r '.results[].name' /tmp/tags.json)"
  NEXT_URL=$(jq -r '.next' /tmp/tags.json)
done
```

**Delete-loop pattern (lines 259-275) — includes the DOCKERHUB_REPOSITORY path-segment bug already fixed once; do not hand-retype the DELETE URL, parameterize it:**
```bash
FAILED=0
for TAG in $TAGS; do
  if [ "$TAG" != "${{ needs.build-and-push-docker-image.outputs.image_tag }}" ]; then
    echo "Deleting tag: $TAG"
    DELETE_HTTP_STATUS=$(curl -s -o /tmp/delete-response.json -w "%{http_code}" -X DELETE \
      -H "Authorization: JWT ${HUB_TOKEN}" \
      "https://hub.docker.com/v2/repositories/${{ needs.setup.outputs.base_image_name }}/tags/$TAG/")
    if [ "$DELETE_HTTP_STATUS" != "204" ]; then
      echo "::warning::Delete of tag $TAG failed with HTTP $DELETE_HTTP_STATUS: $(cat /tmp/delete-response.json)"
      FAILED=$((FAILED + 1))
    fi
  fi
done
if [ "$FAILED" -gt 0 ]; then
  echo "::error::$FAILED tag deletion(s) failed -- see warnings above."
  exit 1
fi
```
For nonprod: `needs: [ deploy-to-nonprod, build-and-push-docker-image, setup ]`, `environment: staging`, `if: success()`, replace `${{ needs.setup.outputs.base_image_name }}` with the new nonprod-repo output (see `setup` job extension below) everywhere it appears (3 occurrences: login username stays `$DOCKERHUB_USER`, list URL, delete URL). Also decide the `DOCKERHUB_TOKEN` question (Pitfall 1) before copying — this is the one secret reference in this block that may need `secrets.DOCKERHUB_TOKEN_NONPROD || secrets.DOCKERHUB_TOKEN` per RESEARCH.md's Code Examples "Pattern 3" fallback expression.

---

### `cleanup-unused-image-nonprod` (CI job, event-driven single-digest delete)

**Analog:** `cleanup-unused-image`, `.github/workflows/deploy.yml:278-301` — copy verbatim.

```yaml
cleanup-unused-image:
  runs-on: ubuntu-latest
  needs: [ deploy-to-netcup, build-and-push-docker-image, setup ]
  if: failure()
  steps:
    - name: Remove just-pushed image
      run: |
        echo "Deleting failed tag: ${{ needs.build-and-push-docker-image.outputs.image_tag }}"
        TOKEN=$(curl -s "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${{ needs.setup.outputs.base_image_name }}:pull,push" \
          | jq -r .token)
        DIGEST=$(curl -sI \
          -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
          -H "Authorization: Bearer $TOKEN" \
          "https://registry-1.docker.io/v2/${{ needs.setup.outputs.base_image_name }}/manifests/${{ needs.build-and-push-docker-image.outputs.image_tag }}" \
          | awk '/Docker-Content-Digest/ {print $2}' | tr -d $'\r')
        curl -s -X DELETE \
          -H "Authorization: Bearer $TOKEN" \
          "https://registry-1.docker.io/v2/${{ needs.setup.outputs.base_image_name }}/manifests/$DIGEST"
```
For nonprod: `needs: [ deploy-to-nonprod, build-and-push-docker-image, setup ]`, `environment: staging`, `if: failure()`, swap `base_image_name` output reference to the nonprod repo output. No Docker Hub login needed here (Registry API v2 token exchange is unauthenticated for a public repo's pull/push scope, mirroring production's own comment at line 200-202) — confirm the nonprod repo is also public, or add Basic auth to the token-exchange call if private.

---

### `setup` job extension (config/utility, transform)

**Analog:** `.github/workflows/deploy.yml:13-20`
```yaml
setup:
  runs-on: ubuntu-latest
  outputs:
    base_image_name: ${{ steps.create_environment_variables.outputs.base_image_name }}
  steps:
    - name: Create environment variables
      id: create_environment_variables
      run: echo "base_image_name=$DOCKERHUB_USER/$DOCKERHUB_REPOSITORY" >> "$GITHUB_OUTPUT"
```
Add a second output (e.g. `base_image_name_nonprod`) computed the same way, reading a new top-level `env:` var (e.g. `DOCKERHUB_REPOSITORY_NONPROD: kanban-board-backend-nonprod`, sibling to the existing `env.DOCKERHUB_REPOSITORY` at line 10) — every nonprod cleanup job references this new output instead of `base_image_name`, per RESEARCH.md Pattern 3.

---

### `docs/INFRA_RUNBOOK.md` — secrets table and CI section update (doc)

**Analog:** existing "Repository secrets" table (~line 486 region, e.g. the `NETCUP_SSH_KEY` row) and the nonprod bring-up section (lines 977-1178, e.g. line 1150's "From `/opt/deploy/kanban-board-nonprod/` on the VM, as `deploy`:" manual-invocation style).

Add rows for every new secret this phase introduces (nonprod SSH user/key, any `DOCKERHUB_TOKEN_NONPROD`), and a new subsection documenting the now-automated CI-05 schema-registration and CI-04 health-check steps, following the existing table-row and prose style already used for `NETCUP_SSH_KEY`'s row and the nonprod bring-up narrative.

## Shared Patterns

### GitHub Environments scoping (CI-02)
**Source:** none in-repo yet (first use in this repo) — apply per RESEARCH.md Pattern 1
**Apply to:** every new nonprod job (`environment: staging`) and the two new `register-schemas-*` jobs (`staging` for nonprod half, `production` for the production half)
```yaml
jobs:
  deploy-to-nonprod:
    environment: staging
```

### Per-target concurrency groups (CI-01)
**Source:** `deploy-to-netcup`'s `concurrency` block, `.github/workflows/deploy.yml:165-167`
**Apply to:** `deploy-to-nonprod` only (the other new jobs do not touch the VM's filesystem the way scp/deploy does, so they don't need a concurrency group of their own — mirrors why `flyway-verify`/cleanup jobs today have none)
```yaml
concurrency:
  group: deploy-to-nonprod-vm
  cancel-in-progress: false
```

### Never-echo-a-secret discipline
**Source:** `flyway-verify`'s pooled-endpoint guard, `.github/workflows/deploy.yml:110-124` (`DB_HOST` inspected via `[[ "$DB_HOST" == *"-pooler"* ]]`, never printed)
**Apply to:** any new step touching `DB_HOST`, the new nonprod SSH key, or `DOCKERHUB_TOKEN`(`_NONPROD`) — same "inspect without printing" discipline, no `set -x` on secret-bearing steps.

### Explicit HTTP-status-check + loud failure
**Source:** `cleanup-old-images`'s `TAGS_HTTP_STATUS`/`DELETE_HTTP_STATUS` checks, `.github/workflows/deploy.yml:247-256,266-269` (`::error::`/`::warning::` + non-zero exit, never silently swallowing a bad status)
**Apply to:** `health-check-nonprod`'s retry loop and both new cleanup jobs — same idiom, same failure semantics.

### `needs:`/`if:` chain scoping per environment
**Source:** `cleanup-old-images`/`cleanup-unused-image`'s `needs: [ deploy-to-netcup, build-and-push-docker-image, setup ]` with `if: success()`/`if: failure()` respectively (`.github/workflows/deploy.yml:212-213,280-281`)
**Apply to:** the nonprod-equivalent cleanup jobs — `needs:` must reference `deploy-to-nonprod`, never `deploy-to-netcup` (Anti-Pattern warning in RESEARCH.md).

## No Analog Found

| File/Job | Role | Data Flow | Reason |
|----------|------|-----------|--------|
| `health-check-nonprod`'s retry-loop body | CI job step | request-response (polling) | Genuinely new work — production's deploy job has never itself polled for health in CI (RESEARCH.md Pitfall 3); use the Code Examples loop from RESEARCH.md directly, not a codebase analog |
| One-time nonprod Docker Hub repository creation | provisioning step (not a steady-state CI job) | one-shot HTTP POST | No existing repo-creation step anywhere in this codebase; either a manual Docker Hub web UI action or a one-off scripted `POST /v2/repositories/` using the same JWT pattern as `cleanup-old-images` (not itself a permanent workflow job — out of `deploy.yml`'s job graph) |

## Metadata

**Analog search scope:** `.github/workflows/deploy.yml` (full file, 301 lines, read in one pass), `docs/INFRA_RUNBOOK.md` (targeted grep for secrets table + nonprod section line ranges)
**Files scanned:** 2
**Pattern extraction date:** 2026-08-18

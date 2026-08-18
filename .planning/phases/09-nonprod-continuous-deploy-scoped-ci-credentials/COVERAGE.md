# Phase 9: External API Capability Coverage

**Generated:** 2026-08-18
**Detector:** `api-coverage.cjs --json` over `ROADMAP` Phase 9 section + the three drafted `09-*-PLAN.md`
bodies → `{"detected": true}` (signal: `Docker Hub (Hub API v2 / Registry API v2)`).
**Policy:** INTEGRATE by default. Every OPT-OUT carries a one-line reason.

This phase integrates three external HTTP APIs. None of them is a product surface this project
exposes to its own users — they are provisioning and CI-control-plane APIs. The matrix below is
therefore scoped to *the capabilities this phase's automation actually depends on*, plus the
adjacent capabilities of each API that a reader might reasonably expect to see used, with a reason
recorded wherever they are deliberately not.

---

## API 1 — GitHub REST API (Environments + Actions Secrets)

Accessed via `gh api` / `gh secret` (CLI 2.97.0, token scopes `repo`, `workflow`, verified).
Consumed by plan 09-01 Task 2 and plan 09-02 Task 1.

| Capability | Endpoint / command | Disposition | Notes |
|---|---|---|---|
| Create / update an environment | `PUT /repos/{owner}/{repo}/environments/{name}` | **INTEGRATE** | 09-01 Task 2A creates `production` and `staging` with an empty body (no protection rules, per D-04) |
| Read environments back | `GET /repos/{owner}/{repo}/environments` | **INTEGRATE** | 09-01 Task 2 acceptance criterion asserts `total_count == 2` and `protection_rules` empty on both |
| Set an environment secret | `PUT /repos/{owner}/{repo}/environments/{name}/secrets/{secret}` (via `gh secret set --env`) | **INTEGRATE** | Nine secrets per environment; values fed on stdin/from a path outside the repo, never `--body` |
| List environment secret names | `GET .../environments/{name}/secrets` (via `gh secret list --env`) | **INTEGRATE** | The only readable half — GitHub secrets are write-only by value; both plans assert on names/counts |
| Delete a repository secret | `DELETE /repos/{owner}/{repo}/actions/secrets/{secret}` | **INTEGRATE** | 09-02 Task 1 deletes exactly nine; `NVD_API_KEY` deliberately retained |
| Read workflow runs / jobs | `GET /repos/{owner}/{repo}/actions/runs` (via `gh run list` / `gh run view`) | **INTEGRATE** | Every plan's live verification reads run conclusions and per-job conclusions from this API |
| Environment protection rules (reviewers, wait timer, branch policy) | `PUT .../environments/{name}` with `reviewers` / `wait_timer` / `deployment_branch_policy` | **OPT-OUT** | D-04 locks both environments as fully unattended; adding any rule would contradict a locked decision |
| Environment variables (non-secret) | `POST .../environments/{name}/variables` | **OPT-OUT** | Every per-environment value this phase needs is either a secret or a workflow-level `env:` literal; a third storage location would fragment the inventory the runbook tracks |
| Deployments / deployment statuses | `POST /repos/{owner}/{repo}/deployments` | **OPT-OUT** | Declaring `environment:` on a job already produces GitHub's deployment records automatically; hand-creating them would duplicate that with no consumer |
| OIDC federated credentials | `id-token: write` + provider trust | **OPT-OUT** | Neither the Netcup VM (SSH) nor Docker Hub accepts GitHub OIDC as an identity; there is nothing to federate to |
| `repository_dispatch` into the frontend repo | `POST /repos/{owner}/{repo}/dispatches` | **OPT-OUT** | Tracked as FRONTEND-DISPATCH-V2 in REQUIREMENTS.md "Future Requirements" — hard-blocked on the frontend repo existing |

## API 2 — Docker Hub Hub API v2 (`hub.docker.com/v2`)

Consumed by plan 09-01 Task 2C (repository creation) and plan 09-02 Task 3A (retention sweep).

| Capability | Endpoint | Disposition | Notes |
|---|---|---|---|
| JWT login-token exchange | `POST /v2/users/login/` | **INTEGRATE** | Mandatory: this API rejects HTTP Basic auth on mutating calls (bug found live 2026-08-16, 29/29 deletes rejected). The nonprod copy inherits the fixed form, including the empty/`null` token guard |
| Create a repository | `POST /v2/repositories/` | **INTEGRATE** | One-time provisioning of `kanban-board-backend-nonprod` as **public**; Docker Hub does not reliably auto-create on first push (RESEARCH.md Pitfall 2). Web-UI creation is an equally valid execution mechanism |
| Read repository metadata | `GET /v2/repositories/{ns}/{repo}/` | **INTEGRATE** | Acceptance criterion asserts `is_private == false`, which is what lets the VM pull without `docker login` |
| List tags, paginated | `GET /v2/repositories/{ns}/{repo}/tags/?page_size=100` + follow `next` | **INTEGRATE** | The `next`-following loop is mandatory: the unpaginated form silently swept only page 1 and left 32 of 41 tags (bug found live 2026-08-17) |
| Delete a tag | `DELETE /v2/repositories/{ns}/{repo}/tags/{tag}/` | **INTEGRATE** | Repository path segment is interpolated from `setup.outputs.base_image_name_nonprod`, never hand-typed — the missing-path-segment bug already burned this repo once |
| Set repository description / overview | `PATCH /v2/repositories/{ns}/{repo}/` | **OPT-OUT** | Cosmetic; the nonprod repository is an internal deploy artifact store, not a published image for consumers |
| Repository-scoped access tokens | `POST /v2/access-tokens/` with repository scopes | **OPT-OUT** | Per-repository token scoping is a Pro/Team-plan feature and this account's tier is unverified (RESEARCH.md A1); Option A was taken at 09-01 Task 1 with the residual recorded — see threat T-09-07 |
| Webhooks on push | `POST /v2/repositories/{ns}/{repo}/webhooks/` | **OPT-OUT** | The deploy is already driven by the same workflow run that pushed the image; a webhook would add a second, racing trigger path |
| Organisation / team permissions | `/v2/orgs/...` | **OPT-OUT** | Single personal namespace (`rudenkovladimir`); there is no org or team to manage |

## API 3 — Docker Registry API v2 (`auth.docker.io`, `registry-1.docker.io`)

Consumed by plan 09-02 Task 3B (failed-deploy image cleanup) and, indirectly, by `docker push` /
`docker compose pull`.

| Capability | Endpoint | Disposition | Notes |
|---|---|---|---|
| Anonymous bearer-token exchange | `GET https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull,push` | **INTEGRATE** | Works unauthenticated because both repositories are public — the same rationale production's script records inline |
| Resolve a manifest digest | `HEAD /v2/{repo}/manifests/{tag}` reading `Docker-Content-Digest` | **INTEGRATE** | Registry v2's manifest DELETE requires a digest reference, not a tag |
| Delete a manifest by digest | `DELETE /v2/{repo}/manifests/{digest}` | **INTEGRATE (with a caveat)** | Copied from production's `cleanup-unused-image`, which performs **no status check at all** — so whether this call has ever actually deleted anything is unproven in this repo's history. The nonprod copy adds a `::warning::`-only status check (09-02 Task 3B) to answer that on the first real failure-path run without reddening an already-failing workflow. Hardening both copies is Phase 10 scope; recorded as threat T-09-14 |
| Push an image | `PUT /v2/{repo}/manifests/{tag}` (via `docker/build-push-action`) | **INTEGRATE** | One build, two tags — the action pushes both `kanban-board-backend:{sha}` and `kanban-board-backend-nonprod:{sha}` from a single build, sharing layers |
| Pull an image | `GET /v2/{repo}/manifests/{tag}` (via `docker compose pull`) | **INTEGRATE** | Runs on the VM as `deploy-nonprod` with no registry login, which is why the nonprod repository must be public |
| List repository tags | `GET /v2/{repo}/tags/list` | **OPT-OUT** | The Hub API v2 tag listing (above) is already used for the retention sweep and returns the richer metadata; using both would be two sources of truth for the same question |
| Content trust / image signing (Notary, cosign) | — | **OPT-OUT** | Supply-chain hardening for `deploy.yml` is Phase 10's scope (HARDEN-03 digest-pinning); adding signing mid-phase would widen this phase beyond CI-01..CI-05 |
| Multi-architecture manifest lists | `PUT /v2/{repo}/manifests/{tag}` with an index | **OPT-OUT** | The only deploy target is x86_64 (Netcup VPS, confirmed via `uname -a`); building a second platform nobody deploys to costs CI minutes for no benefit — the rationale already recorded inline in `deploy.yml` |

---

## Summary

| API | INTEGRATE | OPT-OUT |
|---|---|---|
| GitHub REST (Environments / Secrets / Actions) | 6 | 5 |
| Docker Hub Hub API v2 | 5 | 4 |
| Docker Registry API v2 | 5 | 3 |
| **Total** | **16** | **12** |

Every OPT-OUT above carries a reason grounded in a locked decision (D-04), an out-of-scope boundary
(Phase 10's HARDEN-*, FRONTEND-DISPATCH-V2), a verified environmental fact (single namespace, x86_64
target, unverified Docker Hub plan tier), or a duplicate-source-of-truth argument. No capability is
opted out on grounds of difficulty.

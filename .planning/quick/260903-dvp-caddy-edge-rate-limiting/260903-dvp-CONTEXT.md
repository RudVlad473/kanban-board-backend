---
quick_id: 260903-dvp
slug: caddy-edge-rate-limiting
date: 2026-09-03
status: planning
---

# Context — Edge rate limiting via a custom, pinned Caddy image

## Problem

`/api/signin` and `/api/signup` are unauthenticated and each attempt costs a bcrypt hash on a
2 GB Netcup VPS that already runs a resource-capped Redpanda. There is no rate limiting anywhere
in the stack today.

## Locked decisions (settled with the user before planning — do NOT re-litigate)

- **D-1 — Limit at the Caddy edge, not app-level Bucket4j.** The highest-value limit is IP-keyed
  and pre-authentication, which is exactly what the edge can see. It also sidesteps the fact that
  `server.forward-headers-strategy` is unset in both `application.properties` files and the
  `Caddyfile` has no `trusted_proxies`, so `request.getRemoteAddr()` currently returns Caddy's
  container IP for every request — an app-level per-IP limiter written today would bucket the
  entire internet into one key. No per-user quota is in scope: there is no billing, tenancy, or
  expensive authenticated endpoint that needs one yet.

- **D-2 — Plugin pinned to a raw commit SHA, not the tag.**
  `github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db`.
  `v0.1.0` is the only tag ever cut and `master` is 7 commits ahead of it, including
  `fix(metrics): re-register collectors on each config reload (#102)` (2026-06-12). `xcaddy`
  delegates to `go get module@<sha>`, which still resolves through `sum.golang.org`, so pinning by
  SHA loses discoverability but not integrity.
  **Consequence to document in-file:** Dependabot can never track this plugin — no future tag will
  exist — so the plugin half of the update reminder is manual/scheduled, never Dependabot.

- **D-3 — CI builds and pushes the image; the VPS only pulls.** The VM is 2 GB with a documented
  OOM history; compiling Go there per deploy is not viable. `deploy.yml:374` currently pulls only
  `app`, so the VM must also pull `caddy`.

- **D-4 — Caddy base pinned to `2.11.4`** (both the `caddy:2.11.4-builder` build stage and the
  `caddy:2.11.4` runtime stage), replacing the floating `caddy:2` at `docker-compose.prod.yml:67`.

- **D-5 — The Caddy image tag is derived from its CONTENTS, not the commit SHA.**
  Tag shape: `2.11.4-rl5625512f` (base version + short plugin SHA). Rationale: `app` is tagged with
  the commit short SHA, so its `image:` reference resolves anew every deploy and Compose recreates
  it. If `caddy` were tagged the same way, every app deploy would also recreate the edge container
  — inverting the invariant `docs/INFRA_ARCHITECTURE.md` and `infra-delivery-scenario.mmd` both
  explicitly document (`caddy` left running, resolved config byte-identical, no-op not a restart).
  A content-derived tag keeps that no-op true and restarts the edge only when the edge actually
  changes.

## Findings that must be handled (discovered during scoping, not optional)

- **F-1 — The Caddyfile is a read-only bind mount and is NEVER reloaded.**
  `docker-compose.prod.yml:87` mounts `./Caddyfile:/etc/caddy/Caddyfile:ro`. `deploy.yml` SCPs a
  new Caddyfile and then runs `docker compose up -d`, which is a no-op for `caddy` because its
  resolved config is unchanged (a bind-mounted file's *content* is not part of Compose's config
  hash). So Caddy keeps serving the config it loaded at container start. **Adding `rate_limit` to
  the Caddyfile alone would silently have zero effect in production.** The deploy must explicitly
  reload Caddy after the SCP. This is a pre-existing latent bug, not one this change introduces —
  any past Caddyfile edit had the same problem.

- **F-2 — The build must target `linux/amd64`.** `build-and-push-docker-image` builds
  `linux/amd64` natively because the runner and the VM share x86_64. The Caddy image must match;
  an arch mismatch would not surface until the VM tried to run it.

- **F-3 — `docs/INFRA_ARCHITECTURE.md`'s own Maintenance Note mandates this doc update.** It names
  "the seven job names and the job graph (`needs:` edges) in `deploy.yml`" and
  "`docker-compose.prod.yml`'s ... `image:` tag interpolation" as facts that, when changed, require
  updating the document *and its diagrams* in the same change. This change alters both.

## Scope

1. `docker/caddy/Dockerfile` — two-stage: `caddy:2.11.4-builder` runs `xcaddy build` with the
   SHA-pinned module; `caddy:2.11.4` receives the binary.
2. `.github/workflows/deploy.yml` — a job that builds + pushes the Caddy image (linux/amd64) to a
   new Docker Hub repo, gated behind `run-tests` like the app image; the VM-side script pulls
   `caddy` alongside `app` and reloads Caddy after the Caddyfile SCP (F-1).
3. `docker-compose.prod.yml` — `caddy` service `image:` → the new pinned reference.
4. `Caddyfile` — `rate_limit` inside the `{$APP_DOMAIN}` block ONLY. Not top-level, and not in the
   `{$APP_DOMAIN_NONPROD}` block: nonprod runs chatty e2e suites that must not be throttled.
   A tight zone keyed on client IP for `/api/signin` and `/api/signup`, and a loose general zone.
5. `.github/dependabot.yml` — add the `docker` ecosystem so the pinned `FROM` lines are tracked.
   Document in-file that this covers the Caddy base version ONLY, never the SHA-pinned plugin (D-2).
6. `docs/INFRA_ARCHITECTURE.md` + `docs/diagrams/infra-physical-deployment.mmd` +
   `docs/diagrams/infra-delivery-scenario.mmd` (and their rendered `.png`s) — reflect the new
   build job, the new image reference, the reload step, and the rate-limited edge. Per
   `docs/DIAGRAM_CONVENTIONS.md`, keep each diagram to its single Kruchten 4+1 view: the new build
   job and reload belong in the Scenario (+1) delivery diagram; the rate-limited edge belongs in
   the Physical/Deployment diagram.

## Constraints

- **Never read or modify** `.env`, `.env.prod`, `.env.nonprod`.
- Must not break: Let's Encrypt cert reuse via the `caddy-data` named volume, the two site blocks,
  the app remaining unreachable except over the internal Compose network, or the nonprod path
  (`docker-compose.nonprod.yml` has no Caddyfile of its own and shares the same Caddy container).
- `docker-compose.prod.yml`'s top-level `name: kanban-board-backend` project pin must be untouched.
- A three-reviewer security review (Claude + Gemini + Codex, merged and verified) runs before push.

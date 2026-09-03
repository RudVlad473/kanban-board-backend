# Review brief — edge rate limiting via a pinned custom Caddy image

Repository: `/home/andre/dev/kanban-board-backend`
Branch under review: `quick/260903-dvp-caddy-edge-rate-limiting`
Diff to review: `git diff main...HEAD` (7 commits, 77ece35..5b9487d)

**Do not modify any file in this repository.** If you need to experiment, copy to `/tmp` and work
there. Report findings only.

## What the change does

Adds IP-keyed rate limiting at the Caddy reverse proxy in front of a Spring Boot API, by replacing
the stock `caddy:2` image with a custom image built by CI that compiles in
`github.com/mholt/caddy-ratelimit` (pinned to commit `5625512f24f6f59d6f64fb3aafe5eecff0b286db`).

Files: `docker/caddy/Dockerfile` (new), `Caddyfile`, `docker-compose.prod.yml`,
`.github/workflows/deploy.yml`, `.github/dependabot.yml`, `scripts/verify-caddy-image-tag.py` (new),
`docs/INFRA_ARCHITECTURE.md`, `docs/diagrams/infra-*.mmd` + `.png`, `.claude/CLAUDE.md`.

## Deployment context you need in order to judge this

- Single Netcup VPS (2 GB RAM, x86_64, Vienna). One `app` container, one `caddy`, one `redpanda`.
  Postgres is external (Neon). No replicas, no Redis, no load balancer or CDN in front of Caddy.
- Caddy is the true internet edge: DNS A-record → VM, ports 80/443 published by Docker. The `app`
  container publishes **no** host port and is reachable only over the internal Compose network.
- Caddy serves TWO site blocks from one container: production (`{$APP_DOMAIN}`) and nonprod
  (`{$APP_DOMAIN_NONPROD}`, proxying a separate `app-nonprod` service). Nonprod deliberately has NO
  rate limit — it runs chatty e2e suites.
- TLS is automatic Let's Encrypt. Certificates live in the `caddy-data` named volume. A repeated
  certificate re-request triggers a week-long LE rate-limit ban, so anything that could cause cert
  loss or re-issuance is a HIGH severity finding.
- Deploys run from GitHub Actions over SSH (`appleboy/ssh-action`), which does NOT fail-fast by
  default — every script in `deploy.yml` sets `set -e` explicitly for this reason.
- `docker-compose.prod.yml` has a top-level `name: kanban-board-backend` project pin. Losing it
  makes Compose start a second stack against fresh empty volumes (this has actually happened before,
  see `docs/INFRA_RUNBOOK.md`).

## Decisions already settled — do NOT re-litigate; judge only whether they are implemented correctly

- **D-1** Rate limiting belongs at the edge, not in the Spring app. (Relevant fact: the app has no
  `server.forward-headers-strategy` set, so `request.getRemoteAddr()` returns Caddy's container IP.)
- **D-2** The plugin is pinned to a raw commit SHA, not tag `v0.1.0`, because `v0.1.0` is the only
  tag ever cut and master is 7 commits ahead of it.
- **D-3** CI builds and pushes the image; the 2 GB VPS only pulls it.
- **D-4** Caddy base pinned to `2.11.4` in both build and runtime stages.
- **D-5** The Caddy image tag is derived from its CONTENTS (`2.11.4-rl5625512f`), not the commit SHA,
  so ordinary app-only deploys leave the edge container running rather than recreating it.

## What to look for, in priority order

### 1. Does this break existing infrastructure? (highest priority)

Judge each of these specifically and say which you actually checked:
- Let's Encrypt certificate survival across the one-time edge container recreate.
- The `app` service remaining unreachable except over the internal Compose network.
- The nonprod path (`docker-compose.nonprod.yml`, `app-nonprod`) continuing to work, unthrottled.
- The `name:` project pin and the `caddy-data` / `caddy-config` volumes being untouched.
- The deploy pipeline's failure semantics: can a broken Caddyfile, a failed reload, or a missing
  image produce a GREEN deploy job while the edge is down? Trace `set -e` and exit-code propagation
  through the `appleboy/ssh-action` scripts.
- Ordering: on the FIRST deploy the running container is still stock `caddy:2`. Does the sequence
  in `deploy.yml` avoid asking a stock Caddy to load a config containing `rate_limit`?

### 2. Security

- Can the rate limiter be bypassed or its key spoofed? The zone key is `{remote_host}`. Verify that
  is the TCP peer address and not attacker-controlled here, and that Docker's port publishing does
  not collapse all source addresses to the bridge gateway (which would bucket the whole internet
  into one key and lock everyone out).
- Is the limiter a denial-of-service vector against legitimate users? Consider shared NAT/CGNAT,
  the IPv6 `/56` prefix grouping, and whether the numbers (auth: 10 per 5 min; general: 120 per min,
  and the two zones COMPOSE rather than partition) can lock out a real user or the monitoring probe.
- Memory: per-key state is a preallocated ring of `maxEvents` timestamps. Is the claimed bound
  (~3.5 KB per distinct address, ~350 MB at 100k addresses) right, and is unbounded growth possible
  on a 2 GB box? Note `caddy` has no `mem_limit` in compose — assess whether that is now dangerous.
- Supply chain: the image is built from a SHA-pinned third-party Go module and two pinned base
  images. Assess the actual residual risk and whether the pinning is airtight (including whether
  `xcaddy`/Go module verification applies).
- Secrets: confirm nothing in the diff leaks credentials, and that no `.env*` content was committed.

### 3. Correctness of the tag-sync gate

`scripts/verify-caddy-image-tag.py` is supposed to make it impossible to merge a state where the
Dockerfile's pins and `docker-compose.prod.yml`'s image literal disagree. Try to construct an
inconsistent state it does NOT catch.

### 4. Documentation accuracy

`docs/INFRA_ARCHITECTURE.md` and the two Mermaid diagrams are meant to describe reality. Check them
against the actual `deploy.yml` and `docker-compose.prod.yml` in this branch — job names, the job
graph, the count of jobs, and which containers get recreated on a deploy. Flag any claim that is now
false.

## Rules for your report — these are not optional

1. **Label every finding as either CONFIRMED-BY-RUNNING or REASONED-ONLY.** State which.
2. **Give the exact command that reproduces each finding.** A finding with no reproduction is a
   hypothesis; label it as such.
3. Give each finding a severity (HIGH / MEDIUM / LOW) and the file:line it anchors to.
4. If you cannot execute something, say so explicitly rather than asserting the outcome.
5. Prefer few high-confidence findings over many speculative ones. Explicitly say what you checked
   and found to be FINE — a clean result on the infra-breakage checklist is a useful output.

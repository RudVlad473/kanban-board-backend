---
quick_id: 260903-dvp
type: quick
autonomous: false
requirements: [D-1, D-2, D-3, D-4, D-5, F-1, F-2, F-3]
files_modified:
  - docker/caddy/Dockerfile
  - Caddyfile
  - scripts/verify-caddy-image-tag.py
  - docker-compose.prod.yml
  - .github/workflows/deploy.yml
  - .github/dependabot.yml
  - docs/INFRA_ARCHITECTURE.md
  - docs/diagrams/infra-physical-deployment.mmd
  - docs/diagrams/infra-physical-deployment.png
  - docs/diagrams/infra-delivery-scenario.mmd
  - docs/diagrams/infra-delivery-scenario.png
  - docs/INFRA_RUNBOOK.md
  - .claude/CLAUDE.md

user_setup:
  - service: docker-hub
    why: "The VM pulls the Caddy image anonymously (D-3). deploy.yml's own comment records that the app repository works this way because it is PUBLIC (`is_private: false`). A repository auto-created by a first push inherits the account's default privacy setting, which may be private — in which case the VM's `docker compose pull caddy` 401s and the FIRST deploy after merge goes red."
    dashboard_config:
      - task: "Create the repository `rudenkovladimir/kanban-board-caddy` and set its visibility to Public BEFORE merging this work."
        location: "hub.docker.com -> Repositories -> Create repository"
    verify: "curl -s https://hub.docker.com/v2/repositories/rudenkovladimir/kanban-board-caddy/ | python3 -c 'import sys,json;print(json.load(sys.stdin).get(\"is_private\"))'  # must print False"

estimate:
  tokens: 150000
  raw_tokens: 100000
  tasks: 7
  confidence: low

must_haves:
  truths:
    - "A `caddy` binary built from `caddy:2.11.4-builder` with `github.com/mholt/caddy-ratelimit` pinned to commit `5625512f24f6f59d6f64fb3aafe5eecff0b286db` reports `v2.11.4` from `caddy version` and lists `http.handlers.rate_limit` in `caddy list-modules` — proven by running both against the built image, not inferred from a green build."
    - "Eleven POSTs to `/api/signin` inside five minutes from one source address are answered 429 with a `Retry-After` header on the production hostname's site block."
    - "The identical burst against the nonprod hostname's site block is never answered 429 — proven on the same binary, in the same process, so the result isolates site-block scoping rather than module presence."
    - "Two different source addresses have independent budgets: exhausting the auth zone from one client leaves a second client unaffected. This is the check that would catch Docker's port publishing collapsing every external client to the bridge gateway address — the exact failure mode D-1 rejects at the app layer, moved one layer up."
    - "After a deploy, the config Caddy is actually RUNNING contains a `rate_limit` handler — read back from the admin API, not assumed from the fact that a file was copied (F-1)."
    - "A Caddyfile that fails to adapt fails the deploy job loudly rather than leaving a green run and a stale edge config."
    - "The image tag `2.11.4-rl5625512f` cannot drift between `docker/caddy/Dockerfile`, `docker-compose.prod.yml` and the CI build job: a committed script recomputes it from the Dockerfile and CI refuses to build when the compose literal disagrees."
    - "`scripts/verify-caddy-image-tag.py` FAILS against a deliberately mismatched compose literal — proven by running it against one — which is what makes it a gate rather than a restated comment."
    - "A routine app deploy (no Caddy change) still leaves the `caddy` container running rather than recreating it, preserving the invariant `docs/INFRA_ARCHITECTURE.md` and `infra-delivery-scenario.mmd` both document."
    - "`docs/INFRA_ARCHITECTURE.md` and both linked diagrams describe the pipeline as it actually is after this change: the new build job, the changed `needs:` graph, the new image reference, the reload step, and the rate-limited edge."
  artifacts:
    - "docker/caddy/Dockerfile — two-stage xcaddy build, literal (not ARG-interpolated) FROM tags"
    - "scripts/verify-caddy-image-tag.py — recomputes the content-derived tag from the Dockerfile and gates the compose literal against it; doubles as CI's tag source via --print-tag"
    - "Caddyfile — a `rate_limit` directive inside the `{$APP_DOMAIN}` block only"
    - ".github/workflows/deploy.yml — a `build-and-push-caddy-image` job, and a reload step in `deploy-to-netcup`"
    - "docs/INFRA_RUNBOOK.md — a dated section recording the live verification, the no-lockout proof procedure, and both rollback levers"
  key_links:
    - "The module registers its OWN Caddyfile directive order (`RegisterDirectiveOrder(\"rate_limit\", \"before\", \"basic_auth\")`, confirmed in caddyfile.go at the pinned SHA). That is why this Caddyfile needs no global options block — and why one must NOT be added: introducing `{ ... }` at the top of the file to declare an order that the module already declares adds a construct with its own failure modes to solve a problem that does not exist."
    - "`caddy:2.11.4-builder` sets `ENV CADDY_VERSION=v2.11.4` and xcaddy infers the Caddy version to build from it (caddyserver/caddy-docker `Dockerfile.builder.tmpl`). So the builder-stage FROM tag, not the runtime-stage FROM tag, is what determines the version of the compiled binary — the runtime FROM only supplies the surrounding filesystem. Both must be the same literal, which is invariant I1 of the verify script."
    - "Dependabot's docker ecosystem parses a literal tag out of a `FROM` line. `FROM caddy:${CADDY_VERSION}` would make the new dependabot.yml entry a silent no-op, so the Dockerfile must repeat the version literal in both stages and the verify script must assert they agree — the duplication is deliberate and mechanically guarded, not an oversight."
    - "F-1 and D-5 are the same mechanism seen from two sides: a bind-mounted file's CONTENT is not part of Compose's config hash, which is exactly why a content-derived image tag keeps `caddy` from being recreated on every app deploy AND why the new Caddyfile needs an explicit reload. Fixing one without the other ships a rate limiter that is never loaded."
    - "`{remote_host}` is the zone key, not `{client_ip}`. Caddy is the true edge here — no CDN, no load balancer, ports 80/443 published straight on the VM — so the TCP peer address IS the client, and it cannot be forged by a completed TLS handshake. `{client_ip}` resolves to the same value today (no `trusted_proxies` is configured anywhere) and becomes X-Forwarded-For-derived, i.e. attacker-supplied, the moment anyone configures one. It is never better here and can silently become worse."
---

<objective>
Put a per-client-IP rate limit in front of `/api/signin` and `/api/signup` at the Caddy edge, by
replacing the floating `caddy:2` image with a CI-built, version- and commit-pinned image carrying
`github.com/mholt/caddy-ratelimit`, and by making the deploy actually reload Caddy so the config
takes effect.

Purpose: both endpoints are unauthenticated and each attempt costs a bcrypt hash on a 2 GB VM with
a documented OOM history. Nothing in the stack limits request rate today.

Output: one new Dockerfile, one new CI job, one new verify script, a `rate_limit` block scoped to
the production hostname only, and the doc/diagram updates `docs/INFRA_ARCHITECTURE.md`'s own
Maintenance Note requires when the job graph and a compose `image:` reference change.
</objective>

<context_fidelity>
**LOCKED — do not revisit, do not offer alternatives.** D-1 (limit at the edge, not app-level
Bucket4j; no per-user quota). D-2 (plugin pinned to raw commit `5625512f24f6f59d6f64fb3aafe5eecff0b286db`,
not the tag). D-3 (CI builds and pushes; the VPS only pulls). D-4 (Caddy base pinned to `2.11.4` in
both stages). D-5 (image tag derived from CONTENTS — `2.11.4-rl5625512f` — never from the commit SHA).

**Verified at planning time — treat as given, do not re-derive.**

| Fact | Source |
|------|--------|
| `caddy:2.11.4` and `caddy:2.11.4-builder` both exist; 2.11.4 is the newest 2.11.x | Docker Hub tags API, 2026-09-03 |
| The module registers its own directive order, `before basic_auth` | `caddyfile.go` at the pinned SHA |
| Module ID is exactly `http.handlers.rate_limit` | `handler.go` at the pinned SHA |
| The builder image sets `ENV CADDY_VERSION=v<version>` and xcaddy builds that version | `caddyserver/caddy-docker` `Dockerfile.builder.tmpl` |
| Per-key state is a preallocated `make([]time.Time, maxEvents)` ring; idle keys are swept on a `sweep_interval` defaulting to 1m | `ringbuffer.go` / `handler.go` at the pinned SHA |
| The module sets `Retry-After` and returns `caddyhttp.Error(http.StatusTooManyRequests, nil)` | `handler.go` at the pinned SHA |
| No CI job and no scheduled workflow sends traffic to production's `/api/signin` — `uptime-check.yml` hits `/api/actuator/health` only (3 requests / 15 min), `health-check-nonprod` hits nonprod only, `register-schemas-production` never crosses Caddy | `.github/workflows/*.yml` |
| The existing image-pruning jobs iterate only `base_image_name` / `base_image_name_nonprod`, so a third repository is structurally out of their reach | `deploy.yml` `cleanup-*` jobs |

**Do not read or modify `.env`, `.env.prod`, `.env.nonprod`.** Everything this plan needs about
them is already inferred from `deploy.yml`'s `--env-file ./.env.prod` usage and its `export
IMAGE_TAG=` precedence comment.

**Comment register.** Match the surrounding infra files' density — these files carry substantive
"why" comments and that is correct here. But do NOT copy their habit of citing `.planning`
identifiers: no `D-01`, no `T-05-12`, no `plan 09-02`, no `11-REVIEW.md` in any committed comment.
Cite things a reader can open — a file path, a URL, a version, a date. Every long comment must be a
decision record: state the observation, date it, and say what would make it false.
</context_fidelity>

<design_alternatives>
## Alternate approaches considered

### (A) How the image tag `2.11.4-rl5625512f` is kept in sync across three files

The tag is a fact about the Dockerfile's contents that two other files must agree with. Three files
that can each be edited independently is a defect waiting to happen: a stale compose literal pulls
an image that is not the one CI built, and nothing errors — the wrong edge just runs.

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **Dockerfile is authoritative; a committed script recomputes the tag from it; CI derives the push tag from the script and refuses to build when `docker-compose.prod.yml`'s literal disagrees** | + One editable source of truth; the other two are derived or asserted-equal, so drift is unmergeable rather than merely discouraged. + The compose file keeps a literal, so a human running `docker compose up -d` on the VM by hand — which `docs/INFRA_RUNBOOK.md` documents as a routine operation — needs no environment variable and cannot accidentally resolve the image to an empty tag. + Same shape as `scripts/verify-postgres-memory-invariant.py`, a precedent this repo already argued for in writing: an uncommitted check is a silent gap, not a gate. + The script also catches the builder-stage/runtime-stage version pair drifting apart, which nothing else would. − A Caddy base bump (Dependabot) needs a second edit in the same PR, or CI fails. | **PICKED.** The extra edit is the point: the failure is loud, at merge time, in the PR that caused it. |
| `image: .../kanban-board-caddy:${CADDY_IMAGE_TAG}`, exported by the deploy SSH script exactly like `IMAGE_TAG` | + Mirrors the `app` service's existing pattern precisely; zero new script. − The `app` pattern exists because its tag changes every commit; this one changes only when the edge changes, so the interpolation buys nothing and costs the invariant. − An unset variable resolves to `repo:` and breaks a manual `docker compose up -d` on the VM — a footgun aimed squarely at whoever is already having a bad day. − Moves the tag out of the file a reviewer reads and into a workflow line, so review no longer sees what production runs. | Rejected. |
| Type the literal in all three places and rely on review | + Nothing to build. − This is the defect, restated as a policy. | Rejected. |

### (B) How the new Caddyfile is applied on the VM (F-1)

`docker compose up -d` is a no-op for `caddy` because a bind-mounted file's content is not part of
Compose's config hash. Something must explicitly apply the copied file.

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **`docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile` after `up -d`** | + Caddy adapts and validates the new config BEFORE swapping it in; on failure the old config keeps serving and the command exits non-zero, so `set -e` reddens the deploy while the edge stays up. + Zero downtime, no TLS re-handshake, no certificate touch, in-flight connections preserved. + This is the mechanism the official Caddy image's own documentation prescribes for a config change in Docker. − Needs `-T`: the SSH action runs a non-interactive shell, and `exec` defaulting to a TTY is a known way to turn a working command into "the input device is not a TTY". | **PICKED.** |
| `docker compose up -d --force-recreate caddy` | + No new command shape; obviously applies the file. − Recreates the edge on EVERY deploy, inverting the exact invariant D-5 was designed to preserve and that two docs assert. − Fails silently in the way that matters: `up -d` returns 0 once the container is STARTED, so a Caddyfile that does not adapt leaves a crash-looping edge behind a green deploy job. `docs/INFRA_ARCHITECTURE.md` already records that same "up -d does not wait for healthy" limit for `app`; here it would mean the site is down. | Rejected on the failure mode, not on the downtime. |
| Bake the Caddyfile into the image | + Config becomes part of the immutable artifact; `up -d` applies it for free. − The Caddyfile is not one of the two inputs to the content-derived tag, so either the tag stops describing the contents or every Caddyfile edit recreates the edge — D-5 fails either way. − The nonprod stack shares this one container without shipping its own Caddyfile, so the image would silently own nonprod's routing too. | Rejected — conflicts with a locked decision. |

### (C) Zone key: `{remote_host}`, not `{client_ip}`

Caddy is the true edge: DuckDNS A-record → VM, ports 80/443 published directly, nothing in front.
`{remote_host}` is the TCP peer address, which a completed TLS handshake cannot forge.
`{client_ip}` is resolved from `X-Forwarded-For` **when `trusted_proxies` is configured** and falls
back to the peer address otherwise — so today the two are identical, and the only way they can ever
differ is if someone later configures `trusted_proxies` and thereby makes the limiter's key
attacker-supplied. Picking `{remote_host}` costs nothing now and removes that future footgun.

Falsifiable: this choice is correct only while nothing proxies in front of Caddy. If a CDN or load
balancer is ever put in front, `{remote_host}` becomes that proxy's address and buckets the whole
internet into one key — at which point `trusted_proxies` plus `{client_ip}` becomes the correct
pair, and this decision must be revisited rather than inherited.

## Non-obvious trade-offs

**Memory — the real cost, and it is per distinct source address.** Per-key state is a preallocated
ring of `maxEvents` `time.Time` values (24 bytes each), not a growable list. So the general zone
costs 120 x 24 = 2,880 B per key and the auth zone 10 x 24 = 240 B, call it ~3.5 KB per address
that touches both, including map and mutex overhead. 100,000 distinct addresses inside one window
is therefore roughly 350 MB — on a box where `caddy` was measured at ~20 MB RSS and the documented
host headroom is ~2.65 GiB. This is bounded, not unbounded: the handler's sweeper (default
`sweep_interval` 1m) evicts keys whose state has aged out, so the working set tracks *distinct
addresses seen within roughly the window*, not all addresses ever seen. Two levers exist if it ever
bites: lower the general zone's `events`, or cap the container.

**Deliberately NOT done: a `mem_limit` on the `caddy` service.** It is the only service in
`docker-compose.prod.yml` without one, and this change is what first makes its memory
attacker-influenced. It is still left alone, because every other cap in that file carries a
measured rung ladder in `docs/INFRA_RUNBOOK.md` and an unmeasured cap on the edge risks
OOM-killing the only thing that answers port 443. File it as a follow-up with a measurement, not as
a guess bolted onto this change.

**The 429 body will not be an RFC 7807 ProblemDetail.** Every other error this API emits is
`application/problem+json` with a stable `code`, produced by `GlobalExceptionHandler` or
`ProblemDetailAuthenticationEntryPoint`. A 429 is generated by Caddy and never reaches the JVM, so
it returns Caddy's plain default error body — a real, deliberate inconsistency in the public
contract. A `handle_errors 429` block emitting a matching JSON envelope would close it, and is NOT
included here because whether the module's `Retry-After` header survives being rewritten by an
error handler is unproven, and shipping an unproven header behaviour alongside the limiter itself
would make a failure ambiguous. Follow-up, with that specific check as its acceptance criterion.

**`log_key` logs the key, and the key is a client IP.** The module's own field comment defaults it
off because "keys can contain sensitive information". Here the key is the source address of a
public HTTPS request, which is the least sensitive thing in any edge log and is already the
container's `json-file` log subject. It is enabled because attributing a 429 after the fact is
otherwise impossible from inside the box. Falsifiable: if it produces no output at Caddy's default
log level, or if it materially inflates the 10m x 3 rotation budget, delete the line — it is one
token and nothing depends on it.

**`ipv4_prefix` stays at its default 0 (per-address); `ipv6_prefix` is set to 56.** An IPv6 client
is routinely handed an entire /64 or /56 and can rotate addresses inside it for free, so
per-address IPv6 limiting is not a limit at all. /56 is one step stricter than the module README's
own /64 example, chosen because a /56 is the common residential and small-VPS delegation boundary.
The cost is that one household or one small site shares one budget, which for an auth endpoint is
the intended behaviour. IPv4 is left per-address deliberately: prefix-grouping IPv4 would collapse
CGNAT'd mobile carriers into single buckets and lock out real users.

**A one-time edge recreate on the first deploy.** The `caddy` service's `image:` changes from
`caddy:2` to the new pinned reference, so that one `up -d` recreates the container and ports 80/443
are unbound for a few seconds — for nonprod too, since `docker-compose.nonprod.yml` has no Caddy of
its own and is served by this same container over the `kanban-edge` network. The Let's Encrypt
certificates survive: `caddy-data` and `caddy-config` are named volumes and are untouched. Every
subsequent app-only deploy leaves the container running, which is the whole point of D-5.

**The plugin's 40-hex commit SHA may trip the pre-commit gitleaks scan.** A bare 40-character hex
string is exactly the shape a generic high-entropy rule looks for. If `.githooks/pre-commit`
refuses the commit, the fix is a narrow `[[rules.allowlists]]` entry in `.gitleaks.toml` scoped to
the offending rule and matched by regex against this specific value, with a description citing what
it actually is — never a path exemption. Follow the shape of the two entries already in that file.
</design_alternatives>

<context>
@Caddyfile
@docker-compose.prod.yml
@.github/workflows/deploy.yml
@.github/dependabot.yml
@docs/INFRA_ARCHITECTURE.md
@docs/DIAGRAM_CONVENTIONS.md
@docs/diagrams/infra-physical-deployment.mmd
@docs/diagrams/infra-delivery-scenario.mmd
@scripts/verify-postgres-memory-invariant.py
</context>

<tasks>

<task type="tracer">
  <name>Task 1: Build the pinned Caddy image and prove the module is in it</name>
  <files>docker/caddy/Dockerfile</files>
  <precondition>Docker is available locally and can reach Docker Hub and proxy.golang.org — `xcaddy build` compiles Caddy from source and resolves `go get github.com/mholt/caddy-ratelimit@&lt;sha&gt;` through `sum.golang.org`. Expect 2-5 minutes on a cold build.</precondition>
  <action>
Create `docker/caddy/Dockerfile` as a two-stage build. Both `FROM` tags must be the **literal**
`2.11.4`, never an `ARG` interpolation: Dependabot's docker ecosystem parses a literal tag out of a
`FROM` line, so `FROM caddy:${CADDY_VERSION}` would make Task 5's dependabot entry a silent no-op.
The builder stage runs a single `xcaddy build` with `--with
github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db`; the runtime stage is
plain `caddy:2.11.4` with `COPY --from=builder /usr/bin/caddy /usr/bin/caddy`. Do not add an
`ENTRYPOINT`, `CMD`, `EXPOSE` or `WORKDIR` — the runtime base already supplies all of them, and
restating them is how they drift from upstream.

Carry these as file comments, written as dated decision records rather than narration of the
`FROM` lines:

- Why the plugin is pinned to a raw commit and not `v0.1.0`: that tag is the only one ever cut,
  `master` is ahead of it including a metrics collector re-registration fix from 2026-06-12, and
  `go get module@&lt;sha&gt;` still verifies through `sum.golang.org`, so a SHA pin loses
  discoverability but not integrity. State the consequence that makes this comment load-bearing:
  **no tooling can ever track this pin.** `.github/dependabot.yml` covers the two `caddy:` base
  tags above and can never cover the plugin, because no future tag will exist for it to compare
  against. Bumping it is a manual, deliberate act.
- Why the version literal appears twice: the builder image sets `CADDY_VERSION` and xcaddy infers
  the version it compiles from that environment variable, so the **builder** tag decides which
  Caddy is built and the **runtime** tag only supplies the surrounding filesystem. They must agree,
  the duplication is forced by Dependabot needing literals, and
  `scripts/verify-caddy-image-tag.py` is what makes disagreement fail rather than ship. Date it and
  name the falsifier: if Dependabot ever learns to resolve `ARG` in `FROM`, this duplication can
  collapse to one `ARG`.
- What is deliberately absent: no global options block is needed in the `Caddyfile` for this
  module, because it registers its own directive order (`before basic_auth`) in its `init()`.
  Record this here, next to the pin, because it is the pin's version that guarantees it.
  </action>
  <verify>
    <automated>docker build -t kanban-caddy:local docker/caddy &amp;&amp; docker run --rm kanban-caddy:local caddy version | grep -q 'v2\.11\.4' &amp;&amp; docker run --rm kanban-caddy:local caddy list-modules | grep -qx 'http\.handlers\.rate_limit' &amp;&amp; docker run --rm --entrypoint sh kanban-caddy:local -c 'command -v wget'</automated>
  </verify>
  <done>
`docker/caddy/Dockerfile` exists with two literal `caddy:2.11.4*` FROM tags and one SHA-pinned
`--with`. The built image reports `v2.11.4` and lists `http.handlers.rate_limit` exactly — both
observed from real command output, pasted into the task record, not inferred from build exit 0.
`wget` is confirmed present in the runtime image (Task 4's post-reload admin-API assertion depends
on it; if it is absent, say so now and Task 4 uses the documented fallback instead).
  </done>
</task>

<task type="auto">
  <name>Task 2: Add rate_limit to the production site block, and prove both directions locally</name>
  <files>Caddyfile</files>
  <action>
Add a `rate_limit` directive **inside the `{$APP_DOMAIN}` site block only** — above the existing
`reverse_proxy app:8080` line. Do not add a global options block at the top of the file. Do not
touch the `{$APP_DOMAIN_NONPROD}` block: nonprod runs chatty e2e suites and its lack of a limit is
the negative control this task proves.

Two zones, both keyed on `{remote_host}`:

- `auth` — `match { path /api/signin /api/signin/ /api/signup /api/signup/ }`, `events 10`,
  `window 5m`, `ipv6_prefix 56`.
- `general` — no matcher (every request to this host), `events 120`, `window 1m`, `ipv6_prefix 56`.

Then `log_key` at the directive's top level. No `distributed`, no `storage`, no `jitter`, no
`sweep_interval` — a single instance needs none of them and `distributed` is explicitly approximate.

Comments to carry, in the file's existing register — each a decision, none restating the directive:

- Why `{remote_host}` and not `{client_ip}`: Caddy is the true edge, so the TCP peer address is the
  client and cannot be forged; `{client_ip}` is X-Forwarded-For-derived once `trusted_proxies` is
  configured, which would make the limiter's key attacker-supplied. Name the falsifier explicitly:
  put a CDN or load balancer in front and this inverts.
- Why both `/api/signin` and `/api/signin/` are listed: Spring Boot 3 does not match a trailing
  slash by default, so the slash form already 404s without reaching bcrypt — the extra literals cost
  nothing and remove the question. Say that, so a future reader does not delete them as redundant
  and then have to re-derive why.
- Why the numbers: 10 per 5 minutes bounds sustained cost at 2 attempts/minute/address while still
  allowing a fumbling human a burst of ten; 120 per minute sits comfortably above this repo's own
  reference 54-request burst (`docs/INFRA_RUNBOOK.md`) and above the scheduled health probe. Note
  that an auth request consumes from both zones — the zones compose, they do not partition.
- Why nonprod is excluded, at the nonprod block: same process, same binary, no directive. State
  that this asymmetry is deliberate and is what makes the negative control meaningful.
- What is deliberately NOT here: a `handle_errors 429` block, so a 429 does not carry the
  `application/problem+json` envelope the rest of this API returns. Say that plainly with the date,
  and name the condition that would close it (proving `Retry-After` survives an error-handler
  rewrite).

Then prove it locally against Task 1's image, before any of it goes near CI. `{$APP_DOMAIN}` set to
a bare port makes Caddy serve that site block on that port with no hostname and no ACME attempt;
the upstream will not resolve, so every allowed request answers 502 and every denied request
answers 429 — the 502→429 transition is the proof, and needs no backend at all.

```bash
run() { docker run --rm -d --name rl-test -p 8081:80 -p 8082:81 \
  -e APP_DOMAIN=":80" -e APP_DOMAIN_NONPROD=":81" \
  -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro" kanban-caddy:local; }

# 1. Auth zone on the PRODUCTION block: expect 502 x10 then 429.
run
for i in $(seq 1 12); do curl -s -o /dev/null -w "$i %{http_code}\n" -X POST http://localhost:8081/api/signin; done
curl -sD- -o /dev/null -X POST http://localhost:8081/api/signin | grep -i '^retry-after'

# 2. NEGATIVE CONTROL, same container, same binary: nonprod block, expect 502 x12 and no 429.
for i in $(seq 1 12); do curl -s -o /dev/null -w "$i %{http_code}\n" -X POST http://localhost:8082/api/signin; done
docker rm -f rl-test

# 3. General zone, from a clean counter: expect ~120 x 502 then 429s.
run
for i in $(seq 1 130); do curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/actuator/health; done | sort | uniq -c
docker rm -f rl-test
```

Restarting the container between steps 2 and 3 is load-bearing: the twelve auth requests also
consumed twelve `general` slots, so a shared counter would move the transition point and make the
result unreadable.

Also validate the file adapts cleanly against the new binary:

```bash
docker run --rm -e APP_DOMAIN=example.test -e APP_DOMAIN_NONPROD=nonprod.example.test \
  -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro" kanban-caddy:local \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```
  </action>
  <verify>
    <automated>grep -q 'rate_limit' Caddyfile &amp;&amp; grep -q '{remote_host}' Caddyfile &amp;&amp; awk '/^\{\$APP_DOMAIN_NONPROD\}/,0' Caddyfile | grep -qv 'rate_limit' &amp;&amp; ! grep -qE '^\{\s*$' Caddyfile &amp;&amp; docker run --rm -e APP_DOMAIN=example.test -e APP_DOMAIN_NONPROD=nonprod.example.test -v "$PWD/Caddyfile:/etc/caddy/Caddyfile:ro" kanban-caddy:local caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile</automated>
    <human-check>Paste the three curl loops' real output. Step 1 must show ten 502s then 429s; the 429 must carry a `Retry-After` header. Step 2 must show twelve 502s and zero 429s. Step 3 must show roughly 120 502s and the remainder 429. If step 2 shows any 429, the directive has leaked out of the production block — stop.</human-check>
  </verify>
  <done>
`rate_limit` exists inside `{$APP_DOMAIN}` and nowhere else; no global options block was added;
`caddy validate` exits 0 against the new binary; and both directions are proven from real output —
the limiter fires on the production block and demonstrably does not fire on the nonprod block.
  </done>
</task>

<task type="auto">
  <name>Task 3: Pin the compose image and make the tag mechanically underivable from anywhere else</name>
  <files>docker-compose.prod.yml, scripts/verify-caddy-image-tag.py</files>
  <action>
**Part A — `scripts/verify-caddy-image-tag.py`.** New committed, re-runnable script in the shape of
`scripts/verify-postgres-memory-invariant.py`: module docstring stating the invariants and the fact
that they must FAIL against a mismatched pair, `import yaml` inside `main()` with the same
`ImportError` message, `FAIL: ...` lines to stdout, exit 1 on any violation, and a single
human-readable success line naming the computed tag.

Invariants, all read from `docker/caddy/Dockerfile` and `docker-compose.prod.yml` — nothing
hardcoded but the two file paths and the tag's shape:

- **I1** — the builder-stage `FROM caddy:&lt;A&gt;-builder` and the runtime-stage `FROM caddy:&lt;B&gt;`
  parse cleanly and `A == B`.
- **I2** — exactly one `--with github.com/mholt/caddy-ratelimit@&lt;sha&gt;` occurrence, and `&lt;sha&gt;` is
  40 hex characters. Reject a version tag here: an accidental `@v0.1.0` would make the computed tag
  unparseable and must fail loudly rather than produce a nonsense tag.
- **I3** — `services.caddy.image` in `docker-compose.prod.yml` ends in `:&lt;A&gt;-rl&lt;sha[:8]&gt;`.

Two output modes so CI has exactly one tag source and cannot retype it:
`--print-tag` prints the computed tag alone (`2.11.4-rl5625512f`) and nothing else;
`--print-compose-image` prints the compose file's full `services.caddy.image` literal. Both must
still run I1/I2 first and exit non-zero rather than print a value derived from a broken file.

**Part B — `docker-compose.prod.yml`.** Replace the `caddy` service's `image: caddy:2` with
`image: rudenkovladimir/kanban-board-caddy:2.11.4-rl5625512f`. Change nothing else in that service:
not `ports`, not the `caddy-data`/`caddy-config` volumes, not the Caddyfile bind mount, not the
top-level `name:` pin.

Comment on that line, as a decision record, dated:

- Why this tag is a literal while `app`'s is `${IMAGE_TAG}`: `app`'s reference must resolve anew
  every commit; this one must NOT, because a reference that changes per commit would recreate the
  edge on every deploy and invert the "left running, no-op not a restart" behaviour
  `docs/INFRA_ARCHITECTURE.md` documents. A literal also means a hand-run `docker compose up -d` on
  the VM needs no exported variable.
- That the literal is not hand-maintained: `scripts/verify-caddy-image-tag.py` recomputes it from
  `docker/caddy/Dockerfile` and CI refuses to build when they disagree. Name the consequence a
  future reader will actually hit: a Dependabot Caddy base bump will fail that gate until this line
  is updated in the same PR, and **that failure is the gate working**, not a flaky check.
  </action>
  <verify>
    <automated>python3 scripts/verify-caddy-image-tag.py &amp;&amp; test "$(python3 scripts/verify-caddy-image-tag.py --print-tag)" = "2.11.4-rl5625512f" &amp;&amp; test "$(python3 scripts/verify-caddy-image-tag.py --print-compose-image)" = "rudenkovladimir/kanban-board-caddy:2.11.4-rl5625512f" &amp;&amp; grep -q 'name: kanban-board-backend' docker-compose.prod.yml &amp;&amp; grep -q 'caddy-data:/data' docker-compose.prod.yml &amp;&amp; grep -q '"443:443"' docker-compose.prod.yml</automated>
    <human-check>Prove the gate can fail, in all three directions, and paste the output of each. (a) Temporarily change the compose tag to `2.11.4-rlDEADBEEF` — the script must exit non-zero naming I3. (b) Temporarily change the runtime-stage FROM to `caddy:2.11.3` — it must exit non-zero naming I1. (c) Temporarily change the `--with` pin to `@v0.1.0` — it must exit non-zero naming I2. Revert all three. A script that only ever passes is a comment with a shebang.</human-check>
  </verify>
  <done>
The compose `caddy` service pulls the pinned custom image; `ports`, both named volumes, the
Caddyfile mount and the project `name:` pin are byte-identical to before. The script passes against
the real files and has been **observed failing** against each of the three deliberate mismatches.
  </done>
</task>

<task type="auto">
  <name>Task 4: Build and push the Caddy image in CI, and make the deploy actually reload Caddy</name>
  <files>.github/workflows/deploy.yml</files>
  <precondition>`rudenkovladimir/kanban-board-caddy` exists on Docker Hub and is **public** — see this plan's `user_setup`. The VM pulls anonymously; a private repository turns the first post-merge deploy red at the `pull` step.</precondition>
  <action>
**Part A — workflow env and `setup`.** Add `DOCKERHUB_REPOSITORY_CADDY: kanban-board-caddy` beside
the two existing repository env vars, and add a `base_image_name_caddy` output to the `setup` job
derived the same way as its siblings. Routing it through `setup` rather than reading the env
directly in the new job is the file's existing convention; follow it.

**Part B — new job `build-and-push-caddy-image`.** `needs: [ setup, run-tests ]`,
`if: success()`, `environment: production` (it resolves `secrets.DOCKERHUB_TOKEN`). Outputs
`caddy_image` (the full pushed reference) and `caddy_image_tag`. Steps, in this order:

1. `actions/checkout@v5`.
2. **Gate + tag derivation, in one step.** Run `python3 scripts/verify-caddy-image-tag.py`, then
   set `TAG=$(python3 scripts/verify-caddy-image-tag.py --print-tag)` and
   `IMAGE="${{ needs.setup.outputs.base_image_name_caddy }}:$TAG"`, then assert
   `[ "$IMAGE" = "$(python3 scripts/verify-caddy-image-tag.py --print-compose-image)" ]` and fail
   with `::error::` naming both values if not. That last assertion is what closes the remaining
   drift axis — the workflow's repository name against the compose file's — which the script alone
   cannot see. Write both into `$GITHUB_OUTPUT`. Placed before every other step so a mismatch costs
   seconds, not a build.
3. `docker/setup-buildx-action@v3`.
4. Docker Hub login, using the file's existing `--password-stdin` step verbatim.
5. `docker/build-push-action@v6` with `context: docker/caddy`, `platforms: linux/amd64` (F-2 — the
   runner and the VM are both x86_64 and an arch mismatch would not surface until the VM tried to
   run it), `load: true`, `push: false`, `cache-from: type=gha`, `cache-to: type=gha,mode=max`.
   Comment why it builds then pushes separately rather than pushing directly: a broken image must
   never reach the registry, and steps 6-7 are what decide that. Comment why the cache matters: the
   image content changes only when `docker/caddy/Dockerfile` changes, so the steady-state cost of
   rebuilding on every push is a cache hit, while any real edit busts it and genuinely recompiles.
6. **Prove the artifact, do not trust the build.** `caddy version` must contain `v2.11.4` and
   `caddy list-modules` must contain the exact line `http.handlers.rate_limit`. A green `xcaddy
   build` is not evidence the module was linked in.
7. **Adapt the committed Caddyfile against the freshly built binary**, with `APP_DOMAIN` and
   `APP_DOMAIN_NONPROD` set to throwaway values, via `caddy validate`. This is the control that
   keeps a bad Caddyfile from ever reaching the VM, where its only remaining failure mode is a
   crash-looping edge.
8. `docker push "$IMAGE"`.

Do NOT add a pruning job for this repository, and say why in a comment on the job: the tag is
content-derived, so at most a handful ever exist, and the current one must stay pullable for a cold
VM restart. Note that the existing `cleanup-*` jobs iterate only `base_image_name` and
`base_image_name_nonprod`, so they cannot reach this repository even by accident.

**Part C — `deploy-to-netcup`.** Add `build-and-push-caddy-image` to `needs:`. In the SSH script,
below the existing `set -e` (do not add a second one; do not add `script_stop`, which this file
already documents as a non-existent input):

- change `pull app` to `pull app caddy` — D-3, the VM pulls and never builds;
- after `up -d`, add the reload:
  `docker compose --env-file ./.env.prod -f docker-compose.prod.yml exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile`
- then assert the RUNNING config actually contains the handler, which is the only thing that closes
  F-1 rather than assuming it:
  `docker compose ... exec -T caddy wget -qO- http://localhost:2019/config/ | grep -q '"handler":"rate_limit"'`
  with an `::error::` message if it does not.

`-T` is required on both: the SSH action runs a non-interactive shell and `exec` defaulting to a
TTY is a well-known way to turn a working command into a hard failure.

Comment the ordering, because it is not obvious and one ordering deadlocks: the reload must run
**after** `up -d`, never before. On the first deploy the running container is still stock `caddy:2`
with no rate-limit module, so validating or reloading the new Caddyfile against it would fail on an
unrecognised directive and block the very deploy that installs the module. After `up -d`, the image
reference has changed and the container has been recreated from the new image, which loaded the new
file at startup — so the reload is a no-op on that first run and is the load-bearing step on every
subsequent run, where the tag has not changed and Compose correctly leaves the container alone.

State the failure semantics in the same comment: if the reload fails, `set -e` reddens the job with
the new `app` already swapped in and the previous Caddy config still serving. The edge stays up and
a human looks — which is the intended end state, not an oversight.

Do NOT add this job to `deploy-to-nonprod`'s `needs:`. That job's own comment records that it
references only nonprod nodes so neither deploy path can gate or fail the other, and nonprod ships
no Caddy of its own.
  </action>
  <verify>
    <automated>python3 -c "import yaml,sys; w=yaml.safe_load(open('.github/workflows/deploy.yml')); j=w['jobs']; assert 'build-and-push-caddy-image' in j; assert 'build-and-push-caddy-image' in j['deploy-to-netcup']['needs']; assert 'build-and-push-caddy-image' not in j['deploy-to-nonprod']['needs']; s=j['deploy-to-netcup']['steps'][-1]['with']['script']; assert 'pull app caddy' in s; assert 'caddy reload' in s; assert 'exec -T caddy' in s; assert 'localhost:2019' in s; print('deploy.yml wiring OK,', len(j), 'jobs')"</automated>
    <human-check>Confirm the workflow is accepted by GitHub (a syntax error surfaces only on push). Confirm the Docker Hub repository reads `is_private: false` using the command in this plan's `user_setup`.</human-check>
  </verify>
  <done>
`deploy.yml` has 14 jobs; `build-and-push-caddy-image` gates `deploy-to-netcup` and does not gate
`deploy-to-nonprod`; the VM-side script pulls both images, reloads Caddy after `up -d`, and asserts
the running config carries the handler. The tag used to push is read from
`scripts/verify-caddy-image-tag.py`, and appears as a literal nowhere in the workflow.
  </done>
</task>

<task type="auto">
  <name>Task 5: Track the Caddy base version with Dependabot — and only the base version</name>
  <files>.github/dependabot.yml</files>
  <action>
Add a third `updates:` entry: `package-ecosystem: "docker"`, `directory: "/docker/caddy"`,
`schedule: { interval: "weekly" }`, `open-pull-requests-limit: 2`.

Three things must be in the entry's comment, all of them load-bearing and none of them restating
the YAML:

- **What this can never cover, and why it is not a gap to be closed later.** It tracks the two
  literal `caddy:2.11.4*` FROM tags. It can never track the rate-limit plugin: that pin is a raw
  commit SHA, `v0.1.0` is the only tag upstream has ever cut, and Dependabot compares against
  released versions. Bumping the plugin is a deliberate manual act — say so here, because this file
  is where someone will come looking to find out why it never opened a PR for it.
- **That a bump PR from this entry will fail CI until `docker-compose.prod.yml` is updated in the
  same PR**, because the image tag is derived from the base version and
  `scripts/verify-caddy-image-tag.py` gates the compose literal against it. That failure is the
  gate working. This is the same per-PR chore the `gradle` entry's own comment already describes
  for `gradle/verification-metadata.xml`; reference that shape so the reader recognises it.
- **Why `directory` is `/docker/caddy` and not `/`.** The root `Dockerfile`'s `gradle:8.7-jdk21`
  and `eclipse-temurin:21-jre-jammy` pins are deliberately out of scope here: bumping the app's
  base images triggers a full production and nonprod redeploy per PR and is a separate decision
  with its own risk profile. State it as a bounded exclusion with a date, not silence — silence
  reads as coverage.
  </action>
  <verify>
    <automated>python3 -c "import yaml; d=yaml.safe_load(open('.github/dependabot.yml')); e=[u for u in d['updates'] if u['package-ecosystem']=='docker']; assert len(e)==1, e; assert e[0]['directory']=='/docker/caddy'; assert e[0]['schedule']['interval']=='weekly'; print('dependabot docker entry OK')" &amp;&amp; grep -q 'caddy-ratelimit\|rate-limit plugin' .github/dependabot.yml</automated>
  </verify>
  <done>
One `docker` ecosystem entry scoped to `/docker/caddy`, whose comment states what it covers, what
it structurally cannot cover, the CI consequence of a bump, and why the root Dockerfile is excluded.
  </done>
</task>

<task type="auto">
  <name>Task 6: Update INFRA_ARCHITECTURE.md and both diagrams (F-3)</name>
  <files>docs/INFRA_ARCHITECTURE.md, docs/diagrams/infra-physical-deployment.mmd, docs/diagrams/infra-physical-deployment.png, docs/diagrams/infra-delivery-scenario.mmd, docs/diagrams/infra-delivery-scenario.png, .claude/CLAUDE.md</files>
  <precondition>That document's own Maintenance Note names the job graph and `docker-compose.prod.yml`'s `image:` interpolation as facts requiring a same-change update. This change alters both, so this task is mandatory, not optional.</precondition>
  <action>
Per `docs/DIAGRAM_CONVENTIONS.md`, each diagram stays in its single Kruchten view. The rate-limited
edge is a property of a node — Physical/Deployment. The new build job and the reload step are steps
in the delivery flow — Scenario (+1). Do not put either in the other.

**`infra-physical-deployment.mmd`.** Extend the `caddy` node/subgraph to say what now runs there:
the custom image (Caddy 2.11.4 plus the SHA-pinned rate-limit module, linux/amd64) and that
per-client-IP limiting applies to the production hostname's site block only. Keep the existing
architecture annotation on the VM node — that annotation is why this convention exists.

**`infra-delivery-scenario.mmd`.** Four edits:

1. Add `build-and-push-caddy-image` as a third branch of the existing `par` block, noting that it
   verifies the module is linked and adapts the committed Caddyfile against the new binary before
   pushing.
2. `docker compose pull app` becomes `pull app caddy`.
3. Add the reload after `up -d`, with a note carrying the mechanism: a bind-mounted file's content
   is not part of Compose's config hash, so without this step a new Caddyfile has zero effect —
   this was true of every prior Caddyfile edit too.
4. Amend the `Comp--xCaddy: left running` note so it states the *condition* rather than an
   unconditional outcome: left running while the content-derived Caddy tag is unchanged, which is
   every app-only deploy; recreated only when the edge itself changes.

**Also correct two statements in these files that are already false**, since leaving a knowingly
wrong claim in a file being edited is worse than the edit:

- The delivery diagram's note that `cleanup-old-images`' DELETE calls are rejected `unauthorized`
  and tags still accumulate. `deploy.yml` documents that bug as fixed (token exchange plus
  pagination). Replace the note with what it does now.
- The prose "all seven `deploy.yml` jobs". There are 13 before this change and 14 after. Correct
  the count and state plainly, with the date, that the Scenario diagram traces the **production**
  delivery path only — the nonprod jobs exist and are deliberately not drawn. Do not attempt to
  draw the nonprod path here; record it as the known limit of this diagram instead.

**`.claude/CLAUDE.md`** — one line under Platform Requirements: Caddy is now a pinned custom image
carrying an edge rate limiter, not the stock `caddy:2`.

**Re-render both PNGs.** Both are linked from `docs/INFRA_ARCHITECTURE.md`; a stale PNG beside an
updated `.mmd` is the failure this step exists to prevent, and it is invisible in a diff.

```bash
pnpm dlx @mermaid-js/mermaid-cli -i docs/diagrams/infra-physical-deployment.mmd -o docs/diagrams/infra-physical-deployment.png
pnpm dlx @mermaid-js/mermaid-cli -i docs/diagrams/infra-delivery-scenario.mmd   -o docs/diagrams/infra-delivery-scenario.png
```

mermaid-cli needs a headless Chrome, which is known to need a manual install step on this machine.
If it fails, export from mermaid.live instead and say that you did. Either way, **confirm the PNGs
actually changed** (`git status --porcelain -- docs/diagrams/`) and open both to check the new
content is legible and no node overlaps — a re-render that silently no-ops looks identical to one
that worked.
  </action>
  <verify>
    <automated>grep -q 'rate' docs/diagrams/infra-physical-deployment.mmd &amp;&amp; grep -q '2.11.4' docs/diagrams/infra-physical-deployment.mmd &amp;&amp; grep -q 'build-and-push-caddy-image' docs/diagrams/infra-delivery-scenario.mmd &amp;&amp; grep -q 'reload' docs/diagrams/infra-delivery-scenario.mmd &amp;&amp; grep -q 'pull app caddy' docs/diagrams/infra-delivery-scenario.mmd &amp;&amp; ! grep -q 'unauthorized' docs/diagrams/infra-delivery-scenario.mmd &amp;&amp; ! grep -q 'all seven' docs/INFRA_ARCHITECTURE.md &amp;&amp; grep -q 'build-and-push-caddy-image' docs/INFRA_ARCHITECTURE.md &amp;&amp; test -n "$(git status --porcelain -- docs/diagrams/)"</automated>
    <human-check>Open both re-rendered PNGs. Confirm each shows the new content, that no label is clipped or overlapping, and that neither diagram has acquired content belonging to the other's Kruchten view.</human-check>
  </verify>
  <done>
The Physical view shows the pinned custom image and the production-only rate limit; the Scenario
view shows the new build job, the two-image pull, and the reload with its mechanism note; the two
false statements are corrected; both PNGs are re-rendered and visually checked; `.claude/CLAUDE.md`
names the custom image.
  </done>
</task>

<task type="checkpoint:human-verify" gate="blocking-human">
  <name>Task 7: Post-merge live verification, then record it in the runbook</name>
  <files>docs/INFRA_RUNBOOK.md</files>
  <precondition>The work is merged and the pipeline has run green through `deploy-to-netcup`. Nothing below can run before that.</precondition>
  <action>
Run every check below and paste the real output. This is the only place the "does it actually work
in production" question gets answered, and each check is chosen so the answer cannot be faked by a
green CI run.

**1. The module is in the running edge.**
`ssh netcup-prod`, then from `/opt/deploy/kanban-board-backend`:
`docker compose --env-file ./.env.prod -f docker-compose.prod.yml exec -T caddy caddy list-modules | grep -x http.handlers.rate_limit`

**2. The reload actually took effect (F-1, the whole point).**
`docker compose ... exec -T caddy wget -qO- http://localhost:2019/config/ | grep -o '"handler":"rate_limit"'`
This reads the config Caddy is *running*, not the file that was copied. A pass here is what
distinguishes this deploy from every previous Caddyfile edit in this repo's history.

**3. The limit fires on production — and the blast radius of proving it.**
From a client whose address you can afford to spend, fire twelve POSTs at
`https://kanban-board-rud-vlad-473.duckdns.org/api/signin` with a deliberately invalid body:

```bash
for i in $(seq 1 12); do
  curl -s -o /dev/null -w "$i %{http_code}\n" -X POST \
    -H 'Content-Type: application/json' -d '{}' \
    https://kanban-board-rud-vlad-473.duckdns.org/api/signin
done
```

Expect 400/401 for the first ten and 429 for the rest, with a `Retry-After` header on the 429.

**State the cost before running it, because it is real and small:** the only consequence is that
*your* address cannot sign in or sign up on production for up to five minutes. It clears itself, it
changes no server state, no account is locked, no other client is affected, and every other
endpoint remains available to you under the general zone. Do not run it from the VM itself and do
not run it from an office or CGNAT egress you share with someone who might be signing in — use a
phone hotspot, or accept the five minutes.

**4. Which direction was checked.** Before the deploy — or equivalently, right now against the
nonprod hostname — the identical burst returns **zero** 429s. Run it against
`https://kanban-board-rud-vlad-473-nonprod.duckdns.org/api/signin` and paste that output too. Same
Caddy process, same binary, same module loaded, no directive in that site block: this proves the
scoping rather than the module's presence, and it proves the check is capable of failing. A test
that passes in both directions is not testing what it claims to.

**5. Source addresses are distinct — the check that would catch the worst failure.**
With your first client still 429'd, fire one request at the same production endpoint from a
*second* client on a different public address (phone on cellular). It must NOT be 429. If it is,
Docker's port publishing is collapsing every external client into one key and the limiter is
bucketing the entire internet into a single budget — precisely the failure mode that rules out an
app-level per-IP limiter here, reappearing one layer up. That would make the limiter actively
harmful and is a stop-and-revert condition, not a tuning note.

**6. The invariant survived.** Confirm `caddy`'s container has not been recreated by a subsequent
app-only deploy: `docker inspect -f '{{.Name}} started={{.State.StartedAt}} restarts={{.RestartCount}}' kanban-board-backend-caddy-1`
before and after the next unrelated push.

**Then write it up** in `docs/INFRA_RUNBOOK.md` as a new dated top-level `##` section, matching the
file's existing per-change record register. It must carry: the six outputs above verbatim; the
no-lockout procedure from check 3 including its stated blast radius, so the next person can re-run
it without asking; the zone numbers actually in force; and both rollback levers:

- **Config-only** (the limiter misbehaves, the image is fine): delete the `rate_limit` block from
  `Caddyfile`, push. The deploy SCPs it and reloads — seconds, zero downtime, no image change, no
  certificate involvement. In a real emergency the same edit can be made on the VM's copy and
  reloaded by hand.
- **Image** (the custom binary misbehaves): revert `docker-compose.prod.yml`'s `caddy` `image:` to
  `caddy:2` and push. `up -d` recreates the container from the stock image; the `caddy-data` and
  `caddy-config` named volumes stay attached, so the existing Let's Encrypt certificates are reused
  and no rate-limited re-issue is triggered.

Follow the file's convention of naming other sections by heading text; do not cite `.planning`
identifiers or D-numbers in the added prose.
  </action>
  <verify>
    <human-check>All six checks run against live production/nonprod with output pasted. Check 4 must show zero 429s on nonprod and check 5 must show the second client unaffected — those two are the ones that can invalidate the whole change, and neither can be inferred from the others.</human-check>
    <automated>grep -q 'rate' docs/INFRA_RUNBOOK.md &amp;&amp; grep -q 'kanban-board-caddy' docs/INFRA_RUNBOOK.md &amp;&amp; grep -q 'localhost:2019' docs/INFRA_RUNBOOK.md</automated>
  </verify>
  <done>
Production answers 429 after ten auth attempts from one address; nonprod never does; a second
address is unaffected; the running config carries the handler; and `docs/INFRA_RUNBOOK.md` holds
the evidence, the repeatable no-lockout procedure with its stated cost, and both rollback levers.
  </done>
</task>

</tasks>

<verification>
- `python3 scripts/verify-caddy-image-tag.py` exits 0, and has been observed exiting non-zero
  against each of its three deliberate mismatches.
- `docker build docker/caddy` succeeds; the image reports `v2.11.4` and lists
  `http.handlers.rate_limit`.
- `caddy validate` accepts the committed `Caddyfile` against that image.
- Locally: the production site block returns 429 after ten auth requests; the nonprod site block
  never does; the general zone denies past ~120/minute.
- `.githooks/pre-commit` accepts the commit. If gitleaks flags the plugin's 40-hex SHA, add a
  narrow `[[rules.allowlists]]` entry scoped to the firing rule with a description of what the
  value is — never a path exemption.
- `./gradlew spotlessCheck` and `./gradlew test` still pass. No Java source is touched by this
  change, so a failure here means something unrelated moved; do not paper over it.
- The three-reviewer security review (Claude + Gemini + Codex, run in parallel, merged, every
  surviving finding verified against the artefacts before it is reported) runs before push, per
  this task's context. The zone key choice, the reload's failure semantics, and the memory bound
  are the three places to point it at.
</verification>

<success_criteria>
1. `/api/signin` and `/api/signup` on the production hostname are rate-limited per source address,
   proven against live production with real output.
2. The nonprod hostname is provably not limited — same process, same binary.
3. Two different source addresses have independent budgets, proven, not assumed.
4. The image tag exists as an editable literal in exactly one file, and CI cannot build when the
   other two disagree.
5. A Caddyfile change now takes effect on deploy, and a Caddyfile that does not adapt fails the
   deploy loudly instead of silently.
6. A routine app deploy still leaves `caddy` running.
7. `docs/INFRA_ARCHITECTURE.md` and both diagrams match the pipeline that actually exists, and both
   PNGs were re-rendered and looked at.
8. `docs/INFRA_RUNBOOK.md` carries the live evidence, the repeatable no-lockout verification with
   its stated blast radius, and both rollback levers.
</success_criteria>

<output>
Create `.planning/quick/260903-dvp-caddy-edge-rate-limiting/260903-dvp-SUMMARY.md` when done.
</output>

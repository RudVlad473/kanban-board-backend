# Custom Caddy image carrying the edge rate limiter (quick task 260903-dvp). Replaces the
# floating `caddy:2` at docker-compose.prod.yml's `caddy` service.
#
# Decision (dated 2026-09-03) -- why the plugin is pinned to a raw commit, not a tag: `v0.1.0` is
# the only tag `github.com/mholt/caddy-ratelimit` has ever cut, and `master` is 7 commits ahead of
# it, including a metrics-collector re-registration fix (`fix(metrics): re-register collectors on
# each config reload (#102)`, 2026-06-12). `xcaddy build --with module@<sha>` still resolves
# through `sum.golang.org` (`go get module@<sha>`), so a SHA pin loses discoverability but not
# integrity. Consequence, load-bearing: no tooling can ever track this pin going forward --
# .github/dependabot.yml's docker-ecosystem entry covers the two `caddy:` base tags below and
# structurally cannot cover this plugin, because no future tag will ever exist for it to diff
# against. Bumping it is a manual, deliberate act, not something a bot will ever open a PR for.
#
# Decision -- why the version literal appears twice below: the builder image sets
# `ENV CADDY_VERSION=v<version>` and xcaddy infers the Caddy version it compiles from that
# variable (caddyserver/caddy-docker's `Dockerfile.builder.tmpl`), so the BUILDER stage's tag is
# what decides which Caddy binary gets built -- the runtime stage's tag only supplies the
# surrounding filesystem the binary is copied into. They must agree, and the duplication here is
# forced: `FROM caddy:${CADDY_VERSION}` would make Dependabot's docker-ecosystem entry a silent
# no-op, because that ecosystem parses a literal tag out of a `FROM` line, not an interpolated
# `ARG`. `scripts/verify-caddy-image-tag.py` is what makes the two tags disagreeing fail loudly
# instead of shipping a mismatched pair. Falsifier: if Dependabot ever learns to resolve `ARG` in
# `FROM`, this duplication can collapse to one `ARG`.
#
# What is deliberately absent -- no global options block is needed in the Caddyfile for this
# module: it registers its own directive order (`RegisterDirectiveOrder("rate_limit", "before",
# "basic_auth")`) in its own `init()`, confirmed in `caddyfile.go` at the pinned SHA below. Noted
# here, next to the pin, because it is this exact pinned version that guarantees it.
FROM caddy:2.11.4-builder AS builder

RUN xcaddy build \
    --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

FROM caddy:2.11.4

COPY --from=builder /usr/bin/caddy /usr/bin/caddy

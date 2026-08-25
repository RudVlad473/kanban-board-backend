---
created: 2026-08-20T00:00:00.000Z
title: "No CSP anywhere; HSTS likely never emitted behind Caddy (no forwarded-headers-strategy); baseline headers unverified on the wire"
area: security
severity: moderate
files:

  - src/main/java/com/vrudenko/kanban_board/security/SecurityConfiguration.java
  - src/main/resources/application.properties
  - Caddyfile

audit_acknowledged:
  milestone: v1.3
  at: 2026-08-25
---

## ASVS 4.0.3 cross-reference

A 33-agent ASVS 4.0.3 Level 2 audit cross-referenced this todo against **V14.4.3** (CSP),
**V14.4.5** (HSTS), **V14.4.6** (Referrer-Policy), **V14.4.7** (X-Frame-Options), and **V3.4.4**
(cookie `__Host-` prefix — a related but separate cookie-hardening item, confirmed genuinely
absent; `Secure` + `Path=/` are already met, no `Domain` attribute is set). This produced one new
confirmed finding and one correction to the finding below:

- **New confirmed finding (V14.4.6):** `Referrer-Policy` is also unset. Spring Security's
  `ReferrerPolicyConfig` does not enable by default, unlike `FrameOptionsConfig`/`HstsConfig` which
  do.

- **Correction (V14.4.7), clearly labeled as such:** `X-Frame-Options` DOES fire by default via
  Spring Security — `FrameOptionsConfig.enable()` sets `XFrameOptionsHeaderWriter` with `DENY` mode
  unconditionally (this todo's Problem section below already states this correctly). The gap on
  that specific header is narrower than a reader might otherwise assume from the todo's title — do
  not claim `X-Frame-Options` is missing.

## Problem

Filed from the OWASP API Security Top 10 audit closing
`.planning/todos/completed/2026-08-13-audit-penetration-testing-and-security-coverage-identify-gap.md`
(API8:2023 Security Misconfiguration).

`SecurityConfiguration.securityFilterChain` never calls `http.headers(...)` at all — neither to
customize nor to disable it — so Spring Security's default `HeadersConfigurer` set applies
unconditionally: `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, and a `Cache-Control`
directive are written unconditionally; `Strict-Transport-Security` (HSTS) is written only when
`request.isSecure()` evaluates true.

**Confirmed absent, both layers:**

- No `server.forward-headers-strategy` property and no `ForwardedHeaderFilter` bean exist anywhere
  in `src/main` (confirmed by reading `application.properties` in full).

- `Caddyfile` (read in full) adds zero `header` directives of its own on either site block — its
  automatic HTTPS (Let's Encrypt via HTTP-01) is a certificate/TLS-termination concern only, and
  does not imply any HSTS/CSP/X-Frame-Options header gets added downstream.

- Both site blocks proxy over plain HTTP internally (`reverse_proxy app:8080` /
  `reverse_proxy app-nonprod:8080`), with no `X-Forwarded-Proto` handling configured on the Spring
  side.

**Consequence:** a real end-user's browser genuinely reaches the app over HTTPS (Caddy terminates
TLS), but because Spring never trusts/reads a forwarded-proto header, `request.isSecure()` almost
certainly evaluates `false` for every request the app itself sees (it only sees the internal plain-
HTTP hop from Caddy) — meaning Spring Security's own HSTS writer likely never fires in production,
despite the deployment being TLS-only end-to-end. This was reasoned from code, not measured live
against the deployed instance (no live/production header probe was feasible from this audit's
execution environment).

**No CSP anywhere, either layer** — Spring Security ships no default Content-Security-Policy
header, and nothing in this codebase or `Caddyfile` adds one.

**New confirmed finding (ASVS V14.4.6): `Referrer-Policy` is also unset.** Spring Security's
`ReferrerPolicyConfig` is not part of the default `HeadersConfigurer` set the way
`FrameOptionsConfig`/`HstsConfig` are — it must be opted into explicitly, and nothing in
`SecurityConfiguration` does so.

**Correction (ASVS V14.4.7): `X-Frame-Options` is not missing.** It fires unconditionally by
default via Spring Security's `FrameOptionsConfig.enable()`, which sets
`XFrameOptionsHeaderWriter` in `DENY` mode without any explicit configuration — the gap on this
specific header is narrower than the todo's title might otherwise suggest; do not claim
`X-Frame-Options` itself is absent.

**Baseline headers (`X-Content-Type-Options`, `X-Frame-Options`, `Cache-Control`) are unconditional
per Spring Security's own documented defaults, so the code-level trace is a strong claim — but no
test in this repo asserts them on a real response.** `SessionCookieAttributesE2ETest` is the one
precedent for asserting response headers over a real socket (it asserts `Set-Cookie` attributes);
no equivalent exists for these baseline security headers.

## Solution

1. **Add explicit `http.headers(...)` configuration** in `SecurityConfiguration` rather than
   relying implicitly on framework defaults — makes the intended header set an explicit,
   reviewable decision instead of an artifact of never having touched the DSL. At minimum: keep
   the defaults Spring Security already provides (don't accidentally weaken them), add a
   `Content-Security-Policy` appropriate for a pure JSON REST API (a restrictive default-src
   policy is a reasonable starting point since this backend serves no HTML itself), and confirm
   HSTS's `includeSubDomains`/`preload` posture is a deliberate choice, not a default.

2. **Fix the HSTS-behind-proxy gap**: either configure `server.forward-headers-strategy=framework`
   (or `native`, depending on which is correct for this embedded-Tomcat + Caddy topology) so
   `request.isSecure()` reflects the real, TLS-terminated client connection, or add a
   `ForwardedHeaderFilter` bean explicitly. Confirm Caddy's `reverse_proxy` directive is already
   forwarding `X-Forwarded-Proto`/`X-Forwarded-For` (Caddy does this by default, but verify against
   the actual `Caddyfile`) before assuming the app-side fix alone is sufficient.

3. **Add a real-socket header-assertion test**, modeled on `SessionCookieAttributesE2ETest`'s
   precedent (`@Tag("realSocket")`, `AbstractAppE2ETest`, asserts on the real `HttpResponse`) —
   confirm `X-Content-Type-Options`, `X-Frame-Options`, the new CSP, and (once the forwarded-
   headers fix lands) HSTS are all actually present on the wire, not just theoretically implied by
   Spring Security's documented default behavior.

4. **Live-verify against the actual deployed instance** once the forwarded-headers fix ships — a
   real `curl -I` against the production/nonprod URL is the only way to confirm HSTS is actually
   emitted end-to-end through the real Caddy hop, which this audit's execution environment could
   not do.

---
created: 2026-08-20T00:00:00.000Z
title: "No MFA / second-factor enrollment path"
area: security
severity: moderate
files:
  - src/main/java/com/vrudenko/kanban_board/security/UserAuthenticationProvider.java
---

## Problem

Filed from a 33-agent ASVS 4.0.3 Level 2 audit (ASVS V2.3.2).

`UserAuthenticationProvider` is the sole `AuthenticationProvider`, doing a single BCrypt password
comparison. No TOTP/U2F/FIDO/WebAuthn code exists anywhere in the codebase (grep confirmed).

## Solution

Add an opt-in TOTP-based second factor: an enrollment endpoint generating/storing a per-user TOTP
secret, a verification step inserted into signin when enrolled, and recovery-code issuance. Scope
as opt-in (not mandatory) to avoid a breaking signin-UX change. Note this is large enough to likely
warrant its own phase rather than a single quick task when picked up.

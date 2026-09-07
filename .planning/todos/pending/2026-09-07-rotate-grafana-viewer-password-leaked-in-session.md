---
created: 2026-09-07T00:00:00.000Z
title: "Rotate the Grafana viewer-role password — pasted into a Claude Code session transcript"
area: security
severity: security
files:

---

## Problem

During the 2026-09-07 `/gsd-execute-phase 12` session (plan 12-04's dashboard verification), the
human pasted a Grafana `viewer`-role username and password directly into the chat so the agent
could log in and check the newly-added dashboards render real data. The credential is now present
in that session's transcript.

Unlike the Grafana `admin` account (whose password the orchestrator has deliberately never read or
typed all phase, per the credential-handling protocol established after the 12-01 leak incident),
this `viewer` credential was typed directly into the conversation by the human, not generated or
handled by the agent — but the exposure is the same class of risk: any storage/indexing of this
session transcript now contains a live, working login (read-only scope, but still real access to
every panel/metric this project's Grafana instance holds, per D-03).

## Solution

Rotate the `viewer` account's password in Grafana (Administration -> Users -> the `viewer` user,
or `grafana-cli admin reset-admin-password`-equivalent for a non-admin account via the UI/API).
Store the new value only in a password manager (matching the `admin` account's existing handling
via `pass`) — never in a repo file, never pasted into a future agent session. If this account's
credential needs to be shared with an agent again for verification, prefer having the human drive
that specific check themselves, or generate a short-lived credential for the session rather than
reusing a long-lived one.

Not started.

# 2026-09-05 — intermittent inbound SYN loss to nonprod (159.195.114.230:443)

Evidence for an **open** netcup support ticket. Kept because the ticket is unresolved and this is
the only record of the measurements it rests on.

| File | What it is |
| --- | --- |
| `netcup-ticket.txt` | The ticket as filed: product id, four failure windows, and the RFC 5737 control that proves the 134.8s give-up is the client kernel abandoning an unanswered SYN, not a configured timeout |
| `probe.sh` | Client-side connect prober. Pairs with `/var/tmp/net-forensics/samples.csv` on the host: a row here with `exitcode != 0` against a flat `passive_opens` there means the SYN never arrived |
| `probe.csv` | 928 samples, 2026-09-05T16:48Z onward |

## Still open, and it recurs

On 2026-09-06 the frontend's CI went red twice on this same host — `connect ECONNREFUSED
159.195.114.230:443` at 09:56Z and 10:02Z (runs 34025768967 attempt 1 and 2) — while the host
answered normally from the dev machine minutes either side. `ECONNREFUSED` is an active refusal
rather than the silent SYN loss the ticket describes, so it may be a second failure mode; it is
recorded here because it is the same host, the same port, and the same "works from one client and
not another" shape.

The frontend e2e suite dials this host, so any recurrence reads as a frontend test failure first.
Check here before treating one as a code defect.

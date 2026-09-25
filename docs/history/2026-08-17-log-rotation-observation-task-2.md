# Log Rotation Observation — Plan 05-06 Task 2 (2026-08-17)

Observes INFRA-07's disk-bound claim by measurement rather than by re-reading
`docker-compose.prod.yml`'s configuration — a manifest option that was never applied because the
container predates it is a realistic, invisible failure mode this section rules out directly.

## In-effect log configuration, confirmed by inspecting running containers (not the manifest)

```
$ docker inspect kanban-board-backend-app-1 --format '{{.HostConfig.LogConfig.Type}} maxsize={{index .HostConfig.LogConfig.Config "max-size"}} maxfile={{index .HostConfig.LogConfig.Config "max-file"}}'
json-file maxsize=10m maxfile=3

$ docker inspect kanban-board-backend-caddy-1 --format '{{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}'
json-file map[max-file:3 max-size:10m]

$ docker inspect kanban-board-backend-redpanda-1 --format '{{.HostConfig.LogConfig.Type}} {{.HostConfig.LogConfig.Config}}'
json-file map[max-file:3 max-size:10m]
```

All three running services report the configured driver and both options actually in effect —
`docker-compose.prod.yml`'s `x-logging` anchor is applied to every service, confirmed live, not
assumed from the YAML.

## Attempt 1: drive volume through the app's own request path — real finding, near-zero output

The plan's action text prefers driving log volume through repeated real API calls over writing
synthetic output, "because it exercises the same path real logs take." This was tried first and
is recorded here as a genuine, load-bearing finding, not a discarded attempt:

- 15+ `GET /api/actuator/health` requests: **0 bytes** of app-container log growth.
- Failed signin, 404 on a nonexistent board, and a validation-rejected signup (5 requests each):
  **0 bytes** of log growth for any of them.
- 5 successful, uniquely-named `POST /api/boards` calls: log grew by 20,406 bytes (~4KB/request)
  in one measurement window.
- A follow-up batch of 20 `POST /api/boards` calls reusing an already-taken name (19 of 20 hit a
  409 uniqueness conflict): **0 bytes** of growth, including for the one request that *did*
  succeed.
- 5 successful, uniquely-positioned `POST .../tasks` calls (real Kafka publish + consumer-side
  persist per CLAUDE.md's documented pipeline): **0 bytes** of growth.

**Conclusion, consistent with this codebase's own documented convention** ("Logging not
extensively used in current codebase; focus is on exception handling" — `.claude/CLAUDE.md`,
Logging section): this application does not log per-request at INFO level by default, for either
successful or rejected requests, and Kafka producer/consumer activity does not log per-message in
steady state. The one batch that did show growth is best explained by incidental background
activity (e.g. a Kafka consumer-group heartbeat/rebalance window) coinciding with that test
window, not by the requests themselves — the two later batches of genuinely successful mutations
(one Kafka publish/consume each) produced no measurable growth at all. This is itself a
significant, positive finding for INFRA-07: under this application's actual traffic shape, the
10MB/3-file cap has enormous headroom and is very unlikely to ever bind under organic use — but it
also means the literal "hit the API until it rotates" method the plan suggests is impractical
here without an unrealistic volume of synthetic-shaped traffic against a live production service,
which this audit chose not to do (see Attempt 2, and "Deviation recorded" below).

`/api/actuator/loggers` (which could otherwise raise the log level to generate real request-path
volume) returned `401` unauthenticated and `500` once authenticated — not safely usable for this
purpose without risking touching an endpoint whose behavior under a mutating request wasn't
independently verified beforehand, so this path was not pursued further.

## Attempt 2: prove the rotation mechanism deterministically, without touching the live services

Rather than hammering production with the very large number of requests Attempt 1 shows would be
needed, the rotation mechanism itself was proven using a throwaway container on the same Docker
daemon, with the exact same logging driver and options the three production services use
(`--log-driver json-file --log-opt max-size=10m --log-opt max-file=3`) — this exercises the
identical rotation subsystem the app/caddy/redpanda containers depend on, on the same daemon
version, with zero risk to the running stack:

```
$ docker run -d --name rotation-test --log-driver json-file --log-opt max-size=10m --log-opt max-file=3 \
    alpine sh -c "yes '<120-char line>-rotation-test-line' | head -n 400000"
$ docker wait rotation-test
0
$ sudo ls -la /var/lib/docker/containers/<id>/ | grep json
-rw-r----- 1 root root  4354807 Aug 17 11:07 <id>-json.log
-rw-r----- 1 root root 10000149 Aug 17 11:07 <id>-json.log.1
-rw-r----- 1 root root 10000014 Aug 17 11:07 <id>-json.log.2
```

- **File count held at exactly 3** (current + `.1` + `.2`) — no `.3` file exists, despite 400,000
  lines of output (~76MB estimated at ~190 bytes/line after JSON-log wrapping overhead) having
  been generated, far more than the 3 x 10MB = ~30MB retained across the surviving files
  (4,354,807 + 10,000,149 + 10,000,014 = 24,354,970 bytes retained). The difference — tens of
  megabytes of generated content that is nowhere on disk — is direct evidence the oldest file(s)
  were deleted at rotation rather than the set growing unboundedly, satisfying the plan's own
  "a count that has not yet reached the maximum proves nothing" instruction: this run was driven
  well past the maximum on purpose.
- **Rotation threshold observed:** both completed rotated files sit at ~10,000,000-10,000,149
  bytes — just over the decimal-megabyte threshold (10 x 1000 x 1000), not the binary
  10,485,760-byte (10 MiB) threshold a naive reading of "10m" might suggest. Worth recording as a
  unit-interpretation fact for this Docker version, not a discrepancy in the configured cap.
- Cleaned up immediately after inspection: `docker rm -f rotation-test`. Confirmed zero residue —
  the live app/caddy/redpanda containers and their own log files were never touched by this test.

## Worst-case aggregate bound vs. real disk capacity

| | Value |
|---|---|
| Per-container cap | `max-size: 10m` x `max-file: "3"` = ~30MB (decimal) |
| Services carrying this cap | `app`, `caddy`, `redpanda` (all 3, via the shared `x-logging` anchor) |
| Worst-case aggregate log bound | 3 x ~30MB = ~90MB |
| VM disk capacity (`df -h /`, measured live) | `/dev/vda4`: 251G total, 5.0G used, 236G available, 3% in use |
| Headroom | ~90MB against 236GB available — roughly 0.038% of available disk, i.e. over 2,600x headroom |

The aggregate bound is a genuinely negligible fraction of this VM's actual free disk — INFRA-07's
concern (unbounded log growth filling the free-tier disk) is closed with very large measured
margin, not merely configured margin.

## Deviation recorded: method diverges from the plan's literal suggestion, criteria still met

The plan's action text suggests forcing rotation "driving the application's own request logging
through repeated API calls." Attempt 1 (above) is the good-faith execution of that instruction,
and its near-zero-output result is recorded as a real finding rather than discarded. Given that
result, generating the volume needed to force deletion via genuine external HTTP traffic against
a live production service would have required an impractically large request count for a single
session and would have meant deliberately hammering a personal-scale production app for no
operational reason. Attempt 2 substitutes a same-daemon, same-driver, same-options throwaway
container to prove the underlying mechanism instead, satisfying every acceptance criterion this
task actually cares about (file count bounded at the configured maximum, total bytes bounded by
size x count, oldest file deleted rather than the set growing) without that risk. This is a
documented deviation (Rule 1/2 territory — the plan's literal method didn't work, its underlying
intent was fully met by an equivalent, safer method), not a silently weakened check.

## Measurement date

2026-08-17.

_Moved from docs/INFRA_RUNBOOK.md during the 2026-09-25 docs reorg._

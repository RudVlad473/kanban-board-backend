# Quick Task 260811-ixj: Test-suite speed measurements

Every timing below follows the plan's measurement protocol: `./gradlew <task> --rerun-tasks
--console=plain`, Gradle's own `BUILD SUCCESSFUL in Xm Ys` line, test count read from
`build/test-results/<task>/TEST-*.xml` via
`grep -ho 'tests="[0-9]*"' ... | cut -d'"' -f2 | awk '{s+=$1} END {print s}'`. Runs are back-to-back
in one session, no `clean` between them, no other heavy work concurrent.

## Machine facts (reproducibility)

- Docker: `8 CPUs, 8298041344 bytes` (≈ 7.728 GiB) — `docker info --format '{{.NCPU}} CPUs,
  {{.MemTotal}} bytes'`
- Git SHA baseline was measured at: `38541942b43ed32c48044a0913830772d8d3d7ce`

## Baseline (unchanged tree)

| Task | Run | Duration | Test count |
|---|---|---|---|
| `test` | 1 | 7m 20s (440s) | 385 |
| `test` | 2 | 7m 7s (427s) | 385 |
| `fastTest` | 1 | 5m 34s (334s) | 348 |
| `fastTest` | 2 | 5m 41s (341s) | 348 |

Both `test` runs and both `fastTest` runs agree on test count (385 and 348 respectively), and the
13s (`test`) / 7s (`fastTest`) run-to-run spread is well inside this project's documented ~18s
variance (`docs/LOCAL_DEV.md`: 232s/224s/242s for a prior `fastTest` series).

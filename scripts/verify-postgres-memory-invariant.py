#!/usr/bin/env python3
"""Recompute docker-compose.prod.yml's postgres memory invariant from the file's
own live values and fail if either inequality breaks (CR-02 / 11-VERIFICATION.md
gap 1). This is the committed form of the check 11-08-PLAN.md's Task 2 originally
ran only as a one-off heredoc -- see 11-REVIEW.md CR-01 (regenerated review) for
why an uncommitted check is a silent gap, not a real gate.

I1: shared_buffers must be at most a quarter of mem_limit.
I2: shared_buffers + max_connections * work_mem must fit within 85% of mem_limit.
Both must FAIL against the pre-11-08 pair (mem_limit=64m, shared_buffers=128MB) --
that is what makes this a real gate rather than a restated comment.
"""

import re
import sys

COMPOSE_PATH = "docker-compose.prod.yml"


def to_mb(value):
    match = re.fullmatch(r"(\d+)\s*([kKmMgG])?", str(value).strip())
    if not match:
        return None
    n, unit = int(match.group(1)), (match.group(2) or "b").lower()
    return {"k": n / 1024, "m": n, "g": n * 1024, "b": n / 1048576}[unit]


def main():
    try:
        import yaml
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    with open(COMPOSE_PATH) as f:
        compose = yaml.safe_load(f)

    postgres = compose["services"]["postgres"]
    fails = []

    cap = to_mb(postgres.get("mem_limit"))
    if cap is None:
        fails.append("postgres mem_limit missing or unparseable")

    command = " ".join(str(arg) for arg in postgres.get("command", []))

    def flag(name, unit=True):
        pattern = name + r"=(\d+)" + (r"\s*MB" if unit else "")
        m = re.search(pattern, command)
        return int(m.group(1)) if m else None

    shared_buffers = flag("shared_buffers")
    work_mem = flag("work_mem")
    max_connections = flag("max_connections", unit=False)

    for name, value in (
        ("shared_buffers", shared_buffers),
        ("work_mem", work_mem),
        ("max_connections", max_connections),
    ):
        if value is None:
            fails.append(f"could not parse {name} from the postgres command list")

    if not fails:
        if shared_buffers * 4 > cap:
            fails.append(
                f"I1 violated: shared_buffers={shared_buffers}MB exceeds a quarter "
                f"of mem_limit={cap:g}MB"
            )
        worst_case = shared_buffers + max_connections * work_mem
        if worst_case > 0.85 * cap:
            fails.append(
                f"I2 violated: shared_buffers+max_connections*work_mem={worst_case}MB "
                f"exceeds 85% of mem_limit={cap:g}MB ({0.85 * cap:.1f}MB)"
            )

    if fails:
        for line in fails:
            print(f"FAIL: {line}")
        return 1

    print(
        f"invariants OK: mem_limit={cap:g}MB shared_buffers={shared_buffers}MB "
        f"work_mem={work_mem}MB max_connections={max_connections} "
        f"worst-case={worst_case}MB ({100 * worst_case / cap:.1f}% of cap)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

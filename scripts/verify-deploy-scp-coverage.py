#!/usr/bin/env python3
r"""Gate: every repo-relative bind-mount source a deploy compose file declares must be transferred
by that environment's own `deploy.yml` SCP step. Same shape as scripts/verify-caddy-image-tag.py and
scripts/verify-compose-ports.py: a committed, re-runnable check, not a comment restating an
invariant that nothing enforces.

WHY this exists, live evidence gathered quick task 260908-sj9 (2026-09-08): docker-compose.prod.yml
bind-mounts host paths relative to the Compose file's own directory on the VM
(/opt/deploy/kanban-board-backend/), so a mount like `./docker/grafana/provisioning` resolves
against whatever exists at that path on the VM, not against the git checkout. The only thing that
puts repo content at that path is deploy-to-netcup's `appleboy/scp-action` step, whose `source:`
string is a hand-maintained, comma-separated list -- and four of the six repo-relative mounts
(Grafana provisioning, Prometheus config, Loki config, Promtail config) were never added to it from
Phase 12 onward. Confirmed live on the VM via `find -printf`: those four paths sat frozen at their
2026-09-07 hand-copy timestamps while `docker-compose.prod.yml`/`Caddyfile`/`docker/postgres-init/
01-create-databases-and-roles.sh` advanced with every CI deploy (2026-09-08 20:28). Fourteen green
deploys never caught it, because nothing compared the two lists. This gate makes that comparison a
pure function of the commit, checkable per pull request.

SCOPE: two (compose file, deploy job) pairs, declared in DEPLOY_SCP_PAIRS below so a third
deploy path cannot be added without touching this list:
  - docker-compose.prod.yml <-> deploy-to-netcup
  - docker-compose.nonprod.yml <-> deploy-to-nonprod (its expected repo-relative set is EMPTY --
    that file has zero host bind mounts of any kind, confirmed by reading it; this pairing exists
    so a FUTURE nonprod bind mount cannot land ungated, mirroring
    scripts/verify-compose-ports.py's own I7 "every file accounted for" reasoning)
Each job's SCP step is located by its `uses:` containing `scp-action`, never by step name or
position -- a renamed step still resolves, a renamed or removed scp-action step fails closed (I3).

Invariants, numbered in both this docstring and the emitted FAIL lines so a red CI line names which
one broke, in which pairing:

I1: COVERAGE. Every service `volumes:` entry in the compose file whose host side is repo-relative
    (starts with `./`) must be covered by at least one entry in that job's SCP `source:` list.
    "Covered" means a `source:` entry, once its own leading `./` is stripped, either (a) equals the
    normalized mount path, (b) is nested UNDER it (the source is `<mount>/<something>`), or (c)
    CONTAINS it (the mount is `<source>/<something>` -- a source directory covering a narrower
    mount inside it). Case (b) is required and is NOT laxity: `./docker/postgres-init` is
    bind-mounted as a whole directory while only the single script inside it
    (`docker/postgres-init/01-create-databases-and-roles.sh`) is ever transferred, and that is the
    intended, deliberate arrangement (Phase 11) -- a future reader tightening this to exact-match
    would break a working, reviewed deploy path.
I2: SOURCES RESOLVE. Every `source:` entry must exist in the checkout. A typo'd path transfers
    nothing and errors nowhere in `appleboy/scp-action` -- this is this bug's whole failure
    signature, so a source that cannot be found is itself a violation, not a warning.
I3: FAIL CLOSED ON AN UNREADABLE PIPELINE. If a named compose file, job, `steps:` list, SCP step,
    `source:` key, or `target:` key cannot be found or parsed, that is a FAIL, never a silent pass.
    A gate that no-ops after a future job rename reads as coverage while providing none -- exactly
    the failure mode this whole gate exists to remove.
I4: ABSOLUTE HOST PATHS ARE OUT OF SCOPE BY CONSTRUCTION -- NOT a hole. A volume host side
    beginning with `/` (e.g. node-exporter's `/:/host:ro,rslave`, cAdvisor's `/rootfs`/`/var/run`/
    `/sys`/`/var/lib/docker`, promtail's `/var/run/docker.sock`) is a host resource, never a repo
    artifact, and this gate skips it deliberately. The opposite reading -- that these are an
    oversight -- is the natural assumption for someone skimming the code, so it is stated here
    rather than left to be rediscovered. A bare named-volume entry (e.g. `postgres-data:/var/lib/
    postgresql/data`, no leading `.` or `/` on the host side) is likewise never repo-relative and is
    silently ignored, for the identical reason.

KNOWN HOLES, enumerated now rather than left to be rediscovered:
  * This reads the COMMITTED files only, never the VM. A file placed on the VM by hand -- exactly
    how the four paths this gate exists for got there in the first place -- is invisible to it. The
    gate proves the deploy pipeline WOULD transfer the right paths on its next run; it cannot prove
    what is currently sitting on the VM right now.
  * This proves the path is TRANSFERRED, never that its CONTENT is APPLIED to a running container.
    A bind-mounted file's content is not part of Compose's config hash, so `docker compose up -d`
    does not recreate a container whose only change is that file's content -- true today for
    Grafana datasources, Prometheus, and Loki/Promtail configs (see docs/INFRA_RUNBOOK.md's
    quick-260908-sj9 section for the full per-consumer breakdown; Grafana *dashboards* are the one
    hot-reloading exception, via its 30s file-provider poll).
  * `rm:` being deliberately absent from both SCP steps (required -- `.env.prod`/`.env.nonprod` live
    in the same target directories and must never be recreated or deleted by this job) means a file
    DELETED from the repo is never removed from the VM. No static check over the committed tree can
    detect a deletion that already happened; this is a one-directional guarantee, not a full sync.
"""

import os
import sys

DEPLOY_SCP_PAIRS = [
    {"compose": "docker-compose.prod.yml", "job": "deploy-to-netcup"},
    {"compose": "docker-compose.nonprod.yml", "job": "deploy-to-nonprod"},
]


def normalize_host_path(raw):
    """Strip a leading `./` from a repo-relative path; leave everything else untouched.

    Pure string manipulation, no filesystem access -- callers decide what to do with the result.
    """
    return raw[2:] if raw.startswith("./") else raw


def find_repo_relative_mounts(compose, label):
    """Pure check: every repo-relative (`./`-prefixed) bind-mount host path in `compose`.

    Returns (fails, mounts) where `mounts` is a sorted list of normalized (leading `./` stripped)
    host paths. Absolute host paths (I4) and bare named-volume entries are silently excluded, by
    construction -- neither starts with `./`. No file access, no printing -- everything a single
    already-parsed document can decide happens here, so a self-test can feed it in-memory documents.
    """
    fails = []

    if not isinstance(compose, dict):
        fails.append(f"{label}: not a mapping -- cannot check any invariant")
        return fails, []

    services = compose.get("services")
    if not isinstance(services, dict):
        fails.append(f"{label}: no mapping `services:` key found -- cannot check any invariant")
        return fails, []

    mounts = set()
    for name, service in services.items():
        if not isinstance(service, dict):
            fails.append(f"{label}: service `{name}` is not a mapping -- cannot check its volumes")
            continue

        volumes = service.get("volumes")
        if volumes is None:
            continue
        if not isinstance(volumes, list):
            fails.append(f"{label}: service `{name}`'s `volumes:` is not a list -- cannot check it")
            continue

        for entry in volumes:
            if isinstance(entry, str):
                # Short syntax: "host:container[:mode]" or a bare named-volume:container form.
                # Split on the FIRST colon only -- a Windows-style drive letter is not a concern on
                # this project's Linux-only deploy target, but a container path or mode suffix
                # legitimately contains further colons in neither of this repo's actual entries, so
                # a plain split(":")[0] is unambiguous here.
                host_side = entry.split(":", 1)[0]
            elif isinstance(entry, dict):
                # Long syntax: {type: bind, source: ..., target: ...}. Not used anywhere in this
                # repo's compose files today, but handled so a future switch to it does not silently
                # fall through this gate.
                host_side = entry.get("source")
            else:
                fails.append(
                    f"{label}: service `{name}` has an unrecognized `volumes:` entry {entry!r} -- "
                    f"cannot check it"
                )
                continue

            if not isinstance(host_side, str):
                continue
            if host_side.startswith("/"):
                continue  # I4: absolute host path, out of scope by construction, not a hole.
            if not host_side.startswith("./"):
                continue  # Bare named volume (no `.`/`/` prefix) -- never repo-relative.

            mounts.add(normalize_host_path(host_side))

    return fails, sorted(mounts)


def find_scp_source_and_target(workflow, job_name, label):
    """Pure check (I3): locate `job_name`'s one `scp-action` step and read its source:/target:.

    Returns (fails, source_list, target). `source_list` is None whenever any I3 condition is
    unmet, signalling the caller to skip I1/I2 for this pairing rather than check against nothing.
    """
    fails = []

    jobs = workflow.get("jobs") if isinstance(workflow, dict) else None
    if not isinstance(jobs, dict):
        fails.append(f"I3 violated in {label}: workflow has no mapping `jobs:` key")
        return fails, None, None

    job = jobs.get(job_name)
    if not isinstance(job, dict):
        fails.append(f"I3 violated in {label}: job `{job_name}` not found in workflow")
        return fails, None, None

    steps = job.get("steps")
    if not isinstance(steps, list):
        fails.append(f"I3 violated in {label}: job `{job_name}` has no `steps:` list")
        return fails, None, None

    scp_steps = [
        s for s in steps if isinstance(s, dict) and "scp-action" in str(s.get("uses", ""))
    ]
    if len(scp_steps) != 1:
        fails.append(
            f"I3 violated in {label}: expected exactly one scp-action step in job `{job_name}`, "
            f"found {len(scp_steps)}"
        )
        return fails, None, None

    with_block = scp_steps[0].get("with")
    if not isinstance(with_block, dict):
        fails.append(
            f"I3 violated in {label}: the scp-action step in job `{job_name}` has no `with:` "
            f"mapping"
        )
        return fails, None, None

    source = with_block.get("source")
    target = with_block.get("target")
    if not isinstance(source, str):
        fails.append(
            f"I3 violated in {label}: the scp-action step in job `{job_name}` has no `source:` key"
        )
    if not isinstance(target, str):
        fails.append(
            f"I3 violated in {label}: the scp-action step in job `{job_name}` has no `target:` key"
        )
    if fails:
        return fails, None, None

    source_list = [s.strip() for s in source.split(",") if s.strip()]
    return fails, source_list, target


def is_covered(mount, source_list):
    """I1: is `mount` (already normalized) covered by any entry in `source_list`?

    Three cases, per the docstring: exact match, source nested under the mount, or the mount nested
    under a source that covers it as a directory. `source_list` entries are normalized the same way
    mounts are, so a source written with or without a leading `./` compares identically.
    """
    for raw in source_list:
        source = normalize_host_path(raw)
        if source == mount:
            return True
        if source.startswith(mount + "/"):
            return True
        if mount.startswith(source + "/"):
            return True
    return False


def check_coverage(mounts, source_list, label):
    """Pure check (I1): every mount in `mounts` is covered by `source_list`."""
    return [
        f"I1 violated in {label}: repo-relative mount `./{mount}` is not covered by any "
        f"`source:` entry"
        for mount in mounts
        if not is_covered(mount, source_list)
    ]


def check_sources_resolve(source_list, label, exists=os.path.exists):
    """Pure-ish check (I2): every `source:` entry exists. `exists` is injectable for the self-test."""
    return [
        f"I2 violated in {label}: source entry `{s}` does not exist in the checkout"
        for s in source_list
        if not exists(s)
    ]


def main():
    args = sys.argv[1:]
    workflow_path = ".github/workflows/deploy.yml"
    if "--workflow" in args:
        workflow_path = args[args.index("--workflow") + 1]

    try:
        import yaml
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    try:
        with open(workflow_path) as f:
            workflow = yaml.safe_load(f)
    except OSError as e:
        print(f"FAIL: I3 violated: could not read workflow file {workflow_path}: {e}")
        return 1

    all_fails = []

    for pair in DEPLOY_SCP_PAIRS:
        compose_path = pair["compose"]
        job_name = pair["job"]
        label = f"{compose_path} <-> {job_name}"

        try:
            with open(compose_path) as f:
                compose = yaml.safe_load(f)
        except OSError as e:
            all_fails.append(f"I3 violated in {label}: could not read compose file {compose_path}: {e}")
            continue

        mount_fails, mounts = find_repo_relative_mounts(compose, compose_path)
        all_fails.extend(mount_fails)

        step_fails, source_list, target = find_scp_source_and_target(workflow, job_name, label)
        all_fails.extend(step_fails)

        if source_list is None:
            continue

        all_fails.extend(check_coverage(mounts, source_list, label))
        all_fails.extend(check_sources_resolve(source_list, label))

    if all_fails:
        for line in all_fails:
            print(f"FAIL: {line}")
        return 1

    pairs_desc = "; ".join(f"{p['compose']} <-> {p['job']}" for p in DEPLOY_SCP_PAIRS)
    print(
        "invariants OK: every repo-relative bind mount in each covered compose file is "
        f"transferred by its own deploy job's SCP source: list ({pairs_desc}); every source: "
        "entry resolves in the checkout"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

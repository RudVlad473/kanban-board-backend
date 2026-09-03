#!/usr/bin/env python3
"""Recompute the Caddy image tag from docker/caddy/Dockerfile's own live values and gate
docker-compose.prod.yml's `services.caddy.image` literal against it (quick task 260903-dvp, D-5).
Same shape as scripts/verify-postgres-memory-invariant.py: a committed, re-runnable check, not a
comment restating an invariant that nothing enforces.

The tag is a fact about the Dockerfile's own contents (base version + plugin commit), and two
other files -- the compose literal and CI's push step -- must agree with it. Three independently
editable places is a defect waiting to happen: a stale compose literal pulls an image that is not
the one CI built, and nothing errors -- the wrong edge just runs. This script makes that drift
unmergeable rather than merely discouraged.

I1: the builder-stage `FROM caddy:<A>-builder` and the runtime-stage `FROM caddy:<B>` in
    docker/caddy/Dockerfile parse cleanly and A == B -- the builder image sets CADDY_VERSION and
    xcaddy infers the version it compiles from that, so a disagreement here silently builds a
    different Caddy than the runtime stage's filesystem implies.
I2: exactly one `--with github.com/mholt/caddy-ratelimit@<sha>` occurrence in the Dockerfile, and
    <sha> is 40 hex characters -- a version tag here (e.g. accidentally `@v0.1.0`) would make the
    computed tag unparseable and must fail loudly rather than produce a nonsense tag.
I3: `services.caddy.image` in docker-compose.prod.yml ends in `:<A>-rl<sha[:8]>`.

All three must FAIL against a deliberately mismatched pair -- that is what makes this a real gate
rather than a restated comment.
"""

import re
import sys

DOCKERFILE_PATH = "docker/caddy/Dockerfile"
COMPOSE_PATH = "docker-compose.prod.yml"

BUILDER_FROM_RE = re.compile(r"^\s*FROM\s+caddy:(\S+?)-builder(?:\s+AS\s+\S+)?\s*$", re.MULTILINE)
RUNTIME_FROM_RE = re.compile(r"^\s*FROM\s+caddy:(\S+)\s*$", re.MULTILINE)
PLUGIN_WITH_RE = re.compile(r"--with\s+github\.com/mholt/caddy-ratelimit@(\S+)")


def parse_dockerfile(text):
    """Returns (fails, builder_tag, runtime_tag, sha) -- I1 and I2."""
    fails = []

    builder_match = BUILDER_FROM_RE.search(text)
    builder_tag = builder_match.group(1) if builder_match else None
    if builder_tag is None:
        fails.append(f"I1 violated: no `FROM caddy:<version>-builder` line found in {DOCKERFILE_PATH}")

    # The runtime stage's FROM line has no `-builder` suffix and no `AS <name>` clause -- the
    # builder-line regex above only matches lines that DO carry `-builder`, so this second regex
    # naturally skips it without needing an explicit negative lookahead.
    runtime_tag = None
    for match in RUNTIME_FROM_RE.finditer(text):
        candidate = match.group(1)
        if not candidate.endswith("-builder"):
            runtime_tag = candidate
    if runtime_tag is None:
        fails.append(f"I1 violated: no plain `FROM caddy:<version>` runtime-stage line found in {DOCKERFILE_PATH}")

    if builder_tag is not None and runtime_tag is not None and builder_tag != runtime_tag:
        fails.append(
            f"I1 violated: builder-stage tag ({builder_tag}) and runtime-stage tag "
            f"({runtime_tag}) disagree -- the builder tag decides which Caddy is compiled "
            f"(CADDY_VERSION), the runtime tag only supplies the filesystem it lands in; "
            f"they must be the identical literal"
        )

    with_matches = PLUGIN_WITH_RE.findall(text)
    sha = None
    if len(with_matches) != 1:
        fails.append(
            f"I2 violated: expected exactly one `--with github.com/mholt/caddy-ratelimit@<sha>` "
            f"occurrence in {DOCKERFILE_PATH}, found {len(with_matches)}"
        )
    else:
        candidate_sha = with_matches[0]
        if re.fullmatch(r"[0-9a-fA-F]{40}", candidate_sha):
            sha = candidate_sha
        else:
            fails.append(
                f"I2 violated: caddy-ratelimit pin `{candidate_sha}` is not a 40-character hex "
                f"commit SHA (D-2 -- this plugin is pinned to a raw commit, never a version tag)"
            )

    return fails, builder_tag, runtime_tag, sha


def main():
    try:
        import yaml
    except ImportError:
        print("FAIL: PyYAML is required (pip install pyyaml)")
        return 1

    args = sys.argv[1:]
    print_tag = "--print-tag" in args
    print_compose_image = "--print-compose-image" in args

    with open(DOCKERFILE_PATH) as f:
        dockerfile_text = f.read()

    fails, builder_tag, runtime_tag, sha = parse_dockerfile(dockerfile_text)
    if fails:
        for line in fails:
            print(f"FAIL: {line}")
        return 1

    tag = f"{builder_tag}-rl{sha[:8]}"

    if print_tag:
        print(tag)
        return 0

    with open(COMPOSE_PATH) as f:
        compose = yaml.safe_load(f)

    try:
        compose_image = compose["services"]["caddy"]["image"]
    except (KeyError, TypeError):
        print(f"FAIL: could not read services.caddy.image from {COMPOSE_PATH}")
        return 1

    if print_compose_image:
        print(compose_image)
        return 0

    if not compose_image.endswith(f":{tag}"):
        print(
            f"FAIL: I3 violated: {COMPOSE_PATH}'s services.caddy.image ({compose_image}) does "
            f"not end in the computed tag :{tag} (derived from {DOCKERFILE_PATH})"
        )
        return 1

    print(f"invariants OK: computed tag={tag} compose image={compose_image}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

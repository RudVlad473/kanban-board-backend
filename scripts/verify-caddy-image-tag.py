#!/usr/bin/env python3
r"""Recompute the Caddy image tag from docker/caddy/Dockerfile's own live values and gate
docker-compose.prod.yml's `services.caddy.image` literal against it (quick task 260903-dvp, D-5).
Same shape as scripts/verify-postgres-memory-invariant.py: a committed, re-runnable check, not a
comment restating an invariant that nothing enforces.

The tag encodes TWO facts about the Dockerfile -- its base version and its plugin commit -- and two
other files, the compose literal and CI's push step, must agree with it. Three independently
editable places is a defect waiting to happen: a stale compose literal pulls an image that is not
the one CI built, and nothing errors -- the wrong edge just runs.

Scope, stated precisely because the earlier wording overclaimed it (2026-09-03 review, F3): the tag
is NOT a hash of the Dockerfile, so it does not change when anything else in the file changes. A
second `--with <other plugin>` on a new RUN line, or a `build:` key added to the compose caddy
service, both pass this check and are caught by nothing downstream. What this script does guarantee
is that the base version and plugin SHA named in the Dockerfile agree with the tag the compose file
pulls -- the axis that actually drifts when someone bumps one and forgets the other. Anything
broader needs a content hash, not this.

Known holes, enumerated 2026-09-03 rather than left to be rediscovered. None is silent -- each is
caught downstream before an image can ship -- so they are documented instead of fixed, because
closing them means parsing Dockerfiles properly and that is a bigger thing than this gate is:
  * I2 checks that the Dockerfile MENTIONS the flag, not that xcaddy RECEIVES it. Any construction
    that mentions it inert passes: inside a heredoc body (`RUN <<EOF ... EOF`), after a no-op
    (`RUN : --with github.com/mholt/caddy-ratelimit@<sha>`), or in a backticked shell comment.
    Confirmed 2026-09-03 for all three. `docker build` may still succeed for the no-op form, but
    the resulting image has no rate_limit module and deploy.yml's `caddy list-modules` step fails
    before any push. Closing this properly means executing the Dockerfile's intent, not reading it.
  * `strip_comments` is whole-line, so commenting out `RUN xcaddy build \` while leaving its
    `--with` continuation line uncommented still satisfies I2. Same downstream catch: the
    continuation becomes an unknown Dockerfile instruction and the build fails.
  * RUNTIME_FROM_RE takes the LAST `FROM caddy:` line, so appending a final non-caddy stage
    (`FROM alpine:3.20`) still passes I1. The `caddy list-modules` step catches the resulting image.
  * A second `--with <a different plugin>` on its own RUN line changes image content without
    changing the tag. This one has NO downstream catch and is the only reason to reach for a
    content hash if this file is ever revisited.

Where it runs: .github/workflows/invariant-checks.yml (push and pull_request), and again inside
deploy.yml's build-and-push-caddy-image job. The first is what makes drift unmergeable; deploy.yml
alone could not, since it triggers on push-to-main only.


I1: the builder-stage `FROM caddy:<A>-builder` and the runtime-stage `FROM caddy:<B>` in
    docker/caddy/Dockerfile parse cleanly and A == B -- the builder image sets CADDY_VERSION and
    xcaddy infers the version it compiles from that, so a disagreement here silently builds a
    different Caddy than the runtime stage's filesystem implies.
I2: exactly one `--with github.com/mholt/caddy-ratelimit@<sha>` occurrence in the Dockerfile, and
    <sha> is 40 hex characters -- a version tag here (e.g. accidentally `@v0.1.0`) would make the
    computed tag unparseable and must fail loudly rather than produce a nonsense tag.
I3: `services.caddy.image` in docker-compose.prod.yml ends in `:<A>-rl<sha[:8]>`.
I4: that same image's REPOSITORY equals EXPECTED_COMPOSE_REPO. I3 constrains only what follows the
    colon, so without this an image in any other namespace carrying the correct tag passes -- a
    registry substitution rather than tag drift, and invisible to a check that only reads the tag.

All four must FAIL against a deliberately mismatched pair -- that is what makes this a real gate
rather than a restated comment.
"""

import re
import sys

DOCKERFILE_PATH = "docker/caddy/Dockerfile"
COMPOSE_PATH = "docker-compose.prod.yml"

# I4's expected repository. Declared here rather than inferred from the compose file, because a
# value read out of the file it is checking cannot disagree with it -- the assertion has to come
# from somewhere the drift does not reach. Change this only alongside a deliberate registry move.
EXPECTED_COMPOSE_REPO = "rudenkovladimir/kanban-board-caddy"

BUILDER_FROM_RE = re.compile(r"^\s*FROM\s+caddy:(\S+?)-builder(?:\s+AS\s+\S+)?\s*$", re.MULTILINE)
RUNTIME_FROM_RE = re.compile(r"^\s*FROM\s+caddy:(\S+)\s*$", re.MULTILINE)
PLUGIN_WITH_RE = re.compile(r"--with\s+github\.com/mholt/caddy-ratelimit@(\S+)")


def strip_comments(text):
    """Blank out `#` comment lines so a commented-out directive cannot satisfy an invariant.

    Found 2026-09-03 (review, F3): PLUGIN_WITH_RE matched `#   --with github.com/mholt/...`, so
    commenting the build line out still computed a valid `-rl<sha>` tag. CI would then build a
    stock Caddy with no rate-limit module and push it under a tag asserting the module is present.
    Whole-line only -- Dockerfile `#` is a comment marker just at the start of a line, and a `#`
    mid-line (in a URL fragment, say) is literal.
    """
    return "\n".join("" if line.lstrip().startswith("#") else line for line in text.splitlines())


def parse_dockerfile(text):
    """Returns (fails, builder_tag, runtime_tag, sha) -- I1 and I2."""
    text = strip_comments(text)
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

    # I4: the REPOSITORY, not just the tag suffix. Added 2026-09-03 (round-2 review) -- I3 alone
    # accepts `attacker/kanban-board-caddy:<the correct tag>`, because `endswith(":<tag>")` says
    # nothing about what precedes the colon. deploy.yml already compared the repository, but only
    # deploy.yml did, and that runs post-merge; invariant-checks.yml -- the gate whose entire
    # purpose is to make drift UNMERGEABLE -- called this script alone and so missed exactly the
    # substitution a PR gate exists to stop. Checked here rather than in a second workflow step so
    # every caller inherits it.
    if compose_image.rsplit(":", 1)[0] != EXPECTED_COMPOSE_REPO:
        print(
            f"FAIL: I4 violated: {COMPOSE_PATH}'s services.caddy.image ({compose_image}) is not "
            f"in the expected repository {EXPECTED_COMPOSE_REPO}. The tag matches, so this is a "
            f"registry substitution rather than tag drift -- confirm it is intentional and update "
            f"EXPECTED_COMPOSE_REPO in {__file__} if so."
        )
        return 1

    print(f"invariants OK: computed tag={tag} compose image={compose_image}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

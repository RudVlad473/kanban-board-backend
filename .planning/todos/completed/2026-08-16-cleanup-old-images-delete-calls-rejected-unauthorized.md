---
created: 2026-08-16T18:20:00.000Z
title: cleanup-old-images' DELETE calls are rejected "unauthorized" -- Docker Hub Hub API v2 needs a JWT token exchange, not Basic auth
area: tooling
severity: minor
resolves_phase: 5
files:
  - .github/workflows/deploy.yml
---

## Problem

`.github/workflows/deploy.yml`'s `cleanup-old-images` job (restored to actually run
by plan 05-05 Task 3, after being permanently skipped since `deploy-to-ec2` was
disabled on 2026-08-04) has a second bug beyond the missing-repository-path-segment
one fixed live during 05-05's execution (commit `595ec08`).

After that fix, the job's `curl -s -X DELETE -u "$DOCKERHUB_USER:$DOCKERHUB_TOKEN" ...`
calls now hit the *correct* URL but every one is rejected:
`{"message":"unauthorized","errinfo":{}}` (confirmed live, run `31963539949`,
2026-08-16 18:14 UTC -- 29 tags attempted, all rejected, 0 deleted).

**Root cause (not yet confirmed by a live test, only by documentation review):**
Docker Hub's Hub API v2 (`hub.docker.com/v2/...`, distinct from the Docker
*Registry* API v2 that `cleanup-unused-image`'s `auth.docker.io`/`registry-1.docker.io`
token-exchange flow already correctly uses) does not accept HTTP Basic auth for
mutating requests. The `GET .../tags/` list call earlier in the same job
succeeding with `-u` is not evidence Basic auth is honored -- it is a public
repository, and an unauthenticated `GET` on a public repo's tag list succeeds
regardless of what's in `-u`. The documented pattern for authenticated Hub API
calls is a two-step exchange: `POST https://hub.docker.com/v2/users/login/` with
a JSON body of `{"username": ..., "password": ...}` (a PAT is valid as the
password) returns a token, which must then be sent as `Authorization: JWT <token>`
(or possibly `Bearer <token>` -- the exact current header scheme was not
independently confirmed this session) on the `DELETE` call.

**Why this wasn't fixed live during 05-05:** fixing it requires either live
credential access to test the login-exchange flow against the real Docker Hub
API (which the executor must never handle directly, per this project's
platform-level credential-handling restriction) or trusting an unverified
guess at the exact header scheme -- both of which would violate this project's
"verify before claiming" standard. Left as a correctly-diagnosed, not-yet-fixed
gap rather than a guessed fix presented as done.

**Not a blocker for Phase 5:** plan 05-05's actual `<acceptance_criteria>` for
Task 3 required both cleanup jobs to be correctly wired (`needs:` naming the new
deploy job) and to "run rather than skip" -- both true; it did not require
`cleanup-old-images`' deletes to actually succeed. The practical effect of this
bug is that Docker Hub tags accumulate unbounded (as they did the whole time
`deploy-to-ec2` was disabled) rather than being pruned to one tag per successful
deploy -- a storage/tidiness issue, not a deploy-correctness or security issue.

## Solution

Add a login-token-exchange step before the delete loop, following the documented
Hub API v2 pattern, then verify live (one real deliberate stale-tag deletion,
confirmed via a follow-up `GET .../tags/` call showing the count actually
dropped) before considering this closed -- do not mark this resolved on
documentation review alone, given the prior false "verified live" claim this
same job's Resolution note already made once (see
`.planning/todos/completed/2026-08-04-re-enable-and-rewrite-the-disabled-deploy-job-after-phase-5.md`'s
2026-08-16 correction).

```yaml
- name: Delete old Docker Hub images
  run: |
    HUB_TOKEN=$(curl -s -H "Content-Type: application/json" -X POST \
      -d '{"username": "'"$DOCKERHUB_USER"'", "password": "'"${{ secrets.DOCKERHUB_TOKEN }}"'"}' \
      "https://hub.docker.com/v2/users/login/" | jq -r .token)
    TAGS=$(curl -s -H "Authorization: JWT ${HUB_TOKEN}" \
      "https://hub.docker.com/v2/repositories/${{ needs.setup.outputs.base_image_name }}/tags/" \
      | jq -r '.results[].name')
    for TAG in $TAGS; do
      if [ "$TAG" != "${{ needs.build-and-push-docker-image.outputs.image_tag }}" ]; then
        echo "Deleting tag: $TAG"
        curl -s -X DELETE -H "Authorization: JWT ${HUB_TOKEN}" \
          "https://hub.docker.com/v2/repositories/${{ needs.setup.outputs.base_image_name }}/tags/$TAG/"
      fi
    done
```

Confirm the exact header scheme (`JWT` vs `Bearer`) against Docker's current
docs or a live test before landing -- the snippet above is a starting point,
not a pre-verified fix.

**Trigger:** any time after this todo is picked up; not gating Phase 5 completion.

## Resolution (2026-08-17)

Fixed in two commits, live-verified, not the guessed header scheme this todo originally flagged
as unconfirmed:

1. **`8a31d85`** — added the login-token exchange exactly as this todo's snippet suggested, and
   confirmed the header scheme empirically: `Authorization: JWT <token>` (not `Bearer`) is
   accepted. Both the tag-list and delete calls now check their HTTP status explicitly and fail
   loudly, rather than trusting a body inspection that a 404 (the original path-segment bug) or
   401 (this bug) would both silently pass. First live run (CI run `32016633112`) succeeded per
   its own status checks — but deleted only 9 of 41 accumulated tags.

2. **`faacda4`** — root-caused the partial deletion: Hub API v2 paginates tag listings at 10/page,
   and the list call only ever read page 1. Fixed by following the response's `next` cursor until
   `null` (starting from `page_size=100` so the common case stays a single request). CI run
   `32017867204` (2026-08-17T09:58Z) is the live proof this is fully closed: `cleanup-old-images`
   deleted all 32 remaining non-current tags, the job's own `FAILED` counter (incremented on any
   non-204 delete response) stayed at 0, and the job concluded success with zero `::warning`/
   `::error` lines.

This satisfies the "verify live, don't trust documentation" standard this todo itself insisted
on (see its Solution section) via the job's own per-call status checking rather than a separate
follow-up `GET` — the mechanism that would have surfaced a partial failure (a nonzero `FAILED`
count driving the job's exit code) is the same one that ran clean in `32017867204`.

Phase 5's INFRA-05 requirement (Docker Hub tags pruned) is now fully satisfied — see
`.planning/phases/05-infra-migration/05-06-SUMMARY.md` coverage item D4.

# Phase 13 review warnings WR-01..WR-06

See .planning/phases/13-introduce-kubernetes/13-REVIEW.md. Highlights: gate record sentence "No threshold was relaxed" contradicts the D-08.1(b) amendment (INFRA_RUNBOOK.md:832); dead k8s/data/postgres-bridge root; no gate covers root docker-compose*.yml ports; stale docs (LOCAL_DEV.md, DOCKER-USER runbook sections, comments citing docker-compose.prod.yml); Recreate strategy causes ~40 s 503 per deploy.

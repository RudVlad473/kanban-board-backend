# Phase 7: Restructure test folder - Discussion Log

> **Audit trail only.** Do not use as input to planning, research, or execution agents.
> Decisions are captured in CONTEXT.md — this log preserves the alternatives considered.

**Date:** 2026-08-09
**Phase:** 7-Restructure test folder
**Areas discussed:** Fixture/setup package layout, @Nested merge candidates, E2ETest suffix / tier downgrade criteria, Output form

---

## Fold pending todos?

| Option | Description | Selected |
|--------|-------------|----------|
| Just the source todo | The other 8 matches are noise — no real relation to test restructuring | ✓ |
| Let me review the list | Show all 9 candidates for manual pick | |

**User's choice:** Asked what "weak keyword overlap" meant, then implicitly accepted the "just the source todo" framing after it was explained (generic words like "planning"/"phase"/"2026" driving the score, not topical relevance).
**Notes:** All 8 other matches confirmed unrelated to test-suite organization.

---

## Fixture/setup package layout

| Option | Description | Selected |
|--------|-------------|----------|
| All into support/ | Move all 5 fixture classes into support/, one flat package | partial |
| Split by concern | Separate packages per concern (containers/, base/, etc.) | partial |
| Leave as-is, just document | No file moves, just clarifying docs | |

**User's choice:** Combined 1 and 2 — "all goes into support and under support we have different folders for different kinds of setup." One package, subdivided by concern.
**Notes:** Exact subfolder names left to planner's discretion.

---

## @Nested merge candidates

| Option | Description | Selected |
|--------|-------------|----------|
| Evaluate + propose in planning | Researcher/planner propose specific merge candidates; executor merges the strong ones | ✓ |
| Evaluation only, no merges this phase | Written candidate list only, defer merging | |

**User's choice:** Evaluate + propose in planning.
**Notes:** None.

---

## E2ETest suffix / tier downgrade criteria

| Option | Description | Selected |
|--------|-------------|----------|
| No real-socket AND no Kafka dependency | Lock the todo's own starting rule now | |
| Research first, decide during planning | Don't lock the rule — let research ground it in what the 23 classes actually assert | ✓ |

**User's choice:** Research first, decide during planning — explicitly said "this is an unsurfaced area, I'm not sure whether we even have any 'no real socket' controllers in our app."
**Notes:** During discussion, grepped the codebase to answer part of the user's uncertainty: 4 `controller/*ControllerTest` classes already use in-process MockMvc; 16 files reference RestAssured/RANDOM_PORT. Confirmed both tiers already exist in practice, but the user still deferred the exact downgrade rule to research rather than deciding from that grep alone.

---

## Output form

| Option | Description | Selected |
|--------|-------------|----------|
| Execute everything decided | Fixture relocation + approved merges + passing tier downgrades all happen as real diffs this phase | ✓ |
| Execute low-risk, defer the rest | Only do fixture relocation now; write up merge/downgrade candidates for later | |

**User's choice:** Execute everything decided.
**Notes:** None.

---

## Claude's Discretion

- Exact `support/` subfolder names/boundaries.
- Specific `@Nested` merge candidates (none pre-selected).
- The concrete E2ETest→in-process downgrade list (entirely research-driven).

## Deferred Ideas

None — discussion stayed within the three items the source todo scoped.

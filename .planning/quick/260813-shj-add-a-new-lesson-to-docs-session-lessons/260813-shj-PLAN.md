---
phase: 260813-shj
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
  - docs/SESSION_LESSONS.md
autonomous: true
requirements: [QUICK-260813-SHJ]

estimate:
  tokens: 15000
  raw_tokens: 10000
  tasks: 1
  confidence: low

must_haves:
  truths:
    - "docs/SESSION_LESSONS.md carries a sixth lesson whose rule extends lesson 1's push-before-phase-execution precondition to quick-task-heavy sessions."
    - "A reader who has just read lesson 1 learns something new from lesson 6 — a checkpoint definition for a session shape that has no waves — rather than re-reading lesson 1's rule in different words."
    - "The new lesson's stated harm stops exactly at the one verified base_mismatch recovery; it does not imply commits were lost or work was damaged."
    - "The file's own authoring contract (next integer, three bolded labels in order, no code example) is satisfied by the new section."
  artifacts:
    - "docs/SESSION_LESSONS.md — one new `### 6.` section appended under `## Lessons`, positioned immediately before `## Adding a lesson`."
  key_links:
    - "New lesson -> lesson 1: the new section must name lesson 1 explicitly and state what it generalizes, so the two read as one principle at two scopes rather than as two competing rules."
    - "New lesson -> `## Adding a lesson` contract: heading number, label set, label order, and the no-code-example clause are all enforced by that section at the bottom of the same file."
---

<objective>
Add lesson 6 to `docs/SESSION_LESSONS.md`, generalizing lesson 1's "push before phase execution, and at wave boundaries" rule into a push-cadence policy that also covers sessions made up of many worktree-isolated quick tasks — a session shape that has no waves to use as a natural checkpoint.

Purpose: lesson 1 was written from a phase-execution incident and scoped its rule to phase execution. The same root cause (worktree isolation forks from `origin/HEAD`, not live local `HEAD`) fired again on 2026-08-13 during a quick-task session, where lesson 1's rule literally did not apply. The file's job is to stop the repo from re-learning this; that requires a rule whose trigger matches the session shape that actually hit it.

Output: one new `###` section in `docs/SESSION_LESSONS.md`. No source-code changes, no other file touched.
</objective>

<approach_analysis>

Per `.claude/CLAUDE.md`'s GSD directives, alternatives considered before this plan was written.

**Approach A (picked) — new `### 6.` section that names and generalizes lesson 1.**
Append a sixth lesson under `## Lessons`, immediately before `## Adding a lesson`, carrying its own evidence (the 2026-08-13 quick-task session) and its own checkpoint definition suited to quick-task sessions, with an explicit sentence tying it back to lesson 1.

**Approach B — rewrite lesson 1 in place to broaden its scope.**
Edit lesson 1's **The rule** paragraph so it covers any session, deleting the phase-execution qualifier.

**Approach C — append a corollary paragraph inside lesson 1.**
Follow the precedent of lesson 2's "Timeout corollary" paragraph: extend lesson 1 with an unnumbered trailing paragraph rather than opening a new numbered section.

| Approach | Pros / Cons | Why picked / rejected |
|----------|-------------|-----------------------|
| **A. New `### 6.` section** | **+** Matches the file's stated additive contract and its `## Adding a lesson` shape exactly. **+** Keeps the 2026-08-13 evidence (quick tasks, no waves, 54 unpushed commits) attached to the rule it justifies, so a future reader can audit the claim. **+** Leaves lesson 1's Phase 4 evidence intact and citable. **−** Costs the reader two sections for one underlying principle, and risks reading as duplication if the connective sentence is weak. | **Picked.** The duplication risk is mitigated in-content (name lesson 1, state what is being extended, give a *different* checkpoint definition). The alternatives each break something structural. |
| **B. Rewrite lesson 1** | **+** One rule, one place, zero duplication risk. **−** Directly contradicts the file's preamble ("additive: new lessons are appended over time, never rewritten wholesale"). **−** Destroys the provenance link between lesson 1's rule and the Phase 4 wave-degradation evidence that produced it. **−** Silently rewrites history a human contributor may have already read and cited. | **Rejected** — violates the file's own governing constraint, and loses evidence for no gain the connective sentence in A cannot deliver. |
| **C. Corollary inside lesson 1** | **+** Precedent exists in the same file (lesson 2's timeout corollary). **+** Physically adjacent to the rule it extends, so the connection is unmissable. **−** Lesson 2's corollary documents a mechanism *inside* that same incident; here the evidence is a separate incident months later with a different trigger. **−** Buries a rule with its own trigger conditions inside a section whose heading advertises phase execution, so someone scanning headings for quick-task guidance never finds it. | **Rejected** — the discoverability cost is the whole point of the lesson. Also incompatible with the task constraint requiring a new `###` section. |

**Non-obvious trade-offs (no perf/memory/security dimension — this is a docs-only change):**
- **Rule-decay risk is the real hazard, not correctness.** A cadence rule with an impractical checkpoint gets ignored, which is worse than no rule because the file then reads as advisory. The checkpoint chosen here (each quick task's closing commit, plus an unconditional push before dispatching any worktree-isolated task) is picked because it lands on a boundary this session *already* had — every quick task ends with a commit — so it adds a single command at a point where work already pauses, rather than inventing a new interrupt.
- **Numbering is a shared-state hazard.** "Next integer" is computed from the file's live contents. Verified as 5→6 while planning (2026-08-13); the task re-checks at write time rather than trusting this plan, because any other session appending a lesson first would silently create two lesson 6s.
- **Over-claiming is the main content hazard.** The verified harm is one `base_mismatch` recovery plus a self-imposed loss of worktree parallelism for the rest of the session. No commits were lost. Writing the lesson as if 54 unpushed commits caused damage would make the file less trustworthy, not more persuasive.

</approach_analysis>

<execution_context>
@$HOME/.claude/gsd-core/workflows/execute-plan.md
@$HOME/.claude/gsd-core/templates/summary.md
</execution_context>

<context>
@.claude/CLAUDE.md
@docs/SESSION_LESSONS.md
</context>

<tasks>

<task type="auto">
  <name>Task 1: Add lesson 6 — push cadence for quick-task-heavy sessions</name>
  <files>docs/SESSION_LESSONS.md</files>
  <read_first>
    Read `docs/SESSION_LESSONS.md` in full before editing — specifically its preamble (the file is additive, never rewritten wholesale), lesson 1 (the direct precedent this new lesson generalizes), and the closing `## Adding a lesson` section, which is the authoring contract this task must satisfy.
  </read_first>
  <precondition>
    The highest existing lesson number in `docs/SESSION_LESSONS.md` is 5 (verified 2026-08-13 while planning). Re-derive it at write time; if it is not 5, use actual-highest-plus-one as the new lesson's number and adjust every count in `<verify>` by the same offset. Halt and report if the `## Lessons` or `## Adding a lesson` headings are missing.
  </precondition>

  <action>
    Insert one new `###` section into `docs/SESSION_LESSONS.md`, placed after the last existing lesson and before the `## Adding a lesson` heading. Number it with the next integer after the highest existing lesson number (6, per the precondition). Give it a short imperative title in the voice of the existing five — it should name the cadence and the session shape it applies to, not restate lesson 1's title.

    The section carries exactly three bolded labels, in this order and no others: **What happened**, **Why**, **The rule**. Do not add a fourth label. Do not add a Java or shell example — the file's closing section states explicitly that `CODE_STYLE.md`'s code-example requirement does not apply here.

    **What happened** — write from the 2026-08-13 session, using only these verified facts:
    - At session start `origin/HEAD` (fcaf81c) was already 22 commits behind local `master` (5a91775), carried over unpushed from prior sessions.
    - The session's first worktree-isolated quick task (260813-h2f) failed cleanup with `worktree.cleanup-wave`'s `base_mismatch`, even though `git merge-base --is-ancestor` independently confirmed the branch's parent genuinely was the correct local `master` commit.
    - Recovery meant bypassing the tool: a manual `git rebase` onto local `master` (a no-op, since the branch's parent already was local `master`), then a manual `git merge --ff-only`, then manual worktree and branch cleanup.
    - Every later quick task that session ran without worktree isolation — sequential, committing directly to `master` — specifically to avoid repeating that failure.
    - By session end roughly a dozen quick tasks (260813-h2f through 260813-q1i) had accumulated 54 local commits before a single push, which happened once, at the very end, and only because the operator asked for it.

    State plainly that no further concrete harm was observed: no commits were lost, and nothing beyond that one recovery broke. The cost that *is* real is the rest of the session running without worktree isolation by choice, and a 54-commit window in which `origin` held none of the session's work. Do not imply more went wrong than that.

    **Why** — the mechanism is the same one lesson 1 already documented: worktree isolation forks a new worktree from `origin/HEAD`, not from live local `HEAD` (documented harness behavior). Add what is genuinely new here, which is why lesson 1's rule did not catch it: the base check keys off `origin/HEAD`, so a stale `origin/HEAD` produces a `base_mismatch` even when the branch's actual parent is correct — the check is not wrong about its own question, it is asking about a ref nobody refreshed. And lesson 1's trigger ("starting phase execution", "wave boundaries") never fires in a quick-task session, which has neither, so a session can be fully compliant with lesson 1 and still walk into this.

    **The rule** — extend, do not restate. Name lesson 1 explicitly and say what is being generalized: the underlying invariant is that `origin/HEAD` must not lag local `HEAD` whenever anything is about to fork from `origin/HEAD`, and phase execution is only one of the session shapes that does. Then give this session shape its own checkpoint, since it has no waves: push at each quick task's closing commit — the boundary such a session already has — and unconditionally before dispatching any worktree-isolated task, whatever kind of work it is. Add the practical corollary this session demonstrated: if a `base_mismatch` shows up despite a branch whose parent verifiably is local `master`, check whether `origin/HEAD` is stale before concluding the tool is broken.

    Keep the prose in the register of the existing five lessons — full sentences, specific identifiers, no bullet lists inside the labels. Match the surrounding markdown exactly: inline code backticks for refs, commands, and task ids; a blank line between paragraphs. Change nothing else in the file — no edits to lessons 1 through 5, the preamble, or the `## Adding a lesson` section.
  </action>

  <verify>
    <automated>cd "$(git rev-parse --show-toplevel)" && f=docs/SESSION_LESSONS.md && [ "$(grep -c '^### ' $f)" = 6 ] && [ "$(grep -c '^## ' $f)" = 2 ] && [ "$(grep -c '^\*\*What happened:\*\*' $f)" = 6 ] && [ "$(grep -c '^\*\*Why:\*\*' $f)" = 6 ] && [ "$(grep -c '^\*\*The rule:\*\*' $f)" = 6 ] && [ "$(grep -cP '^\x60{3}' $f)" = 0 ] && [ -n "$(grep -n '^### 6\.' $f)" ] && [ -z "$(grep -n '^### 7\.' $f)" ] && [ "$(grep -n '^## Adding a lesson' $f | cut -d: -f1)" -gt "$(grep -n '^### 6\.' $f | cut -d: -f1)" ] && echo STRUCTURE_OK</automated>
    <automated>cd "$(git rev-parse --show-toplevel)" && [ "$(git diff --name-only -- docs/SESSION_LESSONS.md | wc -l | tr -d ' ')" = 1 ] && [ "$(git diff --numstat -- docs/SESSION_LESSONS.md | cut -f2)" = 0 ] && echo ADDITIONS_ONLY</automated>
    <human-check>
      Read the new section next to lesson 1 and confirm it earns its place: it names lesson 1, states what it generalizes, and gives a checkpoint lesson 1 does not — rather than re-stating lesson 1's rule in different words. Confirm the harm claim stops at the one `base_mismatch` recovery plus the self-imposed loss of parallelism, with no implication that commits or work were lost.
    </human-check>
  </verify>

  <done>
    `docs/SESSION_LESSONS.md` contains exactly six `###` lessons; the sixth is numbered with the next integer after the previously-highest lesson, sits under `## Lessons` and above `## Adding a lesson`, and carries exactly the three required bolded labels in the required order with no code fence. Its rule extends lesson 1 by name and defines a quick-task-session checkpoint. Lessons 1-5, the preamble, and the `## Adding a lesson` section are byte-identical to their prior state (`git diff --numstat` shows zero deleted lines). No file other than `docs/SESSION_LESSONS.md` is modified.
  </done>
</task>

</tasks>

<threat_model>
## Trust Boundaries

| Boundary | Description |
|----------|-------------|
| (none introduced) | Documentation-only change. No code path, no input parsing, no network or database surface is added or altered. |

## STRIDE Threat Register

| Threat ID | Category | Component | Severity | Disposition | Mitigation Plan |
|-----------|----------|-----------|----------|-------------|-----------------|
| T-shj-01 | Tampering | `docs/SESSION_LESSONS.md` — silent edits to lessons 1-5 riding along with the addition | low | mitigate | `<verify>`'s second automated gate fails the task on any deleted line in the file, so the change is provably additive |
| T-shj-02 | Repudiation | The lesson's factual claims (22-commit lag, `base_mismatch`, 54 commits) | low | mitigate | Every claim in `<action>` is a fact verified during this session and cited with its concrete identifier (`fcaf81c`, `5a91775`, `260813-h2f`, `260813-q1i`), so a later reader can re-derive it from git history rather than trust it |
| T-shj-03 | Information disclosure | Lesson prose | low | accept | Content is commit shas and internal task ids already present throughout `.planning/` and git history in this repository; no credentials, hostnames, or secrets are involved |
</threat_model>

<verification>
- `docs/SESSION_LESSONS.md` structure gate passes (6 `###` lessons, 2 `##` headings, 6 of each bolded label, no fenced code block, new section above `## Adding a lesson`).
- `git diff --numstat -- docs/SESSION_LESSONS.md` reports zero deleted lines — the change is purely additive, as the file's preamble requires.
- `git status --porcelain` shows no other file modified by this task.
- No build or test run is required: no file under `src/` is touched, so `spotlessCheck` and the test suite are unaffected. (The pre-commit hook will still run `spotlessCheck` and `fastTest` on commit — give it a generous timeout per lesson 2's timeout corollary.)
</verification>

<success_criteria>
- Lesson 6 exists, is numbered as the genuine next integer derived from the file at write time, and satisfies the file's `## Adding a lesson` contract exactly.
- Its rule generalizes lesson 1 — same underlying invariant, explicitly cross-referenced — while supplying a checkpoint definition of its own for sessions that have no waves.
- Its evidence is limited to what was verified this session, and its harm claim is bounded to the single `base_mismatch` recovery and the deliberate loss of worktree parallelism.
- Nothing else in the repository changes.
</success_criteria>

<output>
Create `.planning/quick/260813-shj-add-a-new-lesson-to-docs-session-lessons/260813-shj-SUMMARY.md` when done.
</output>

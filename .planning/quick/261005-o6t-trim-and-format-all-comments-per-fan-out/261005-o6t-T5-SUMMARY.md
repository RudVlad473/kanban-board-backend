# T5 summary

status: complete with one documented acceptance-command variance

Commit: `7f4d52d chore(261005-o6t): wire comment policy lint`

Changes:

- Wired the policy checker into the pre-commit hook, with a Python-3 fallback and a loud fail-open warning.
- Added the `comment-lint` invariant-checks job; its self-test precedes its full-tree check.
- Added CODE_STYLE rule 14.
- Taught the checker to join multi-line TODO comments, recognize `planner-discipline-allow:`, and lex Bash ANSI-C quotes.
- Reflowed the residual comment block in `scripts/verify-postgres-init-quoting.sh` found by the whole-tree check.

## Acceptance evidence

### Self-test and each new regression case — confirmed by running it

Command:

```text
python3 scripts/verify-comments-selftest.py
```

Output (the new cases are named explicitly):

```text
PASS: test_planner_discipline_allow_is_a_functional_marker
PASS: test_r4_todo_can_continue_on_following_comment_lines
PASS: test_shell_ansi_c_quote_leaves_later_comments_visible
  (cross-checked 62 YAML files)
40/40 passed
```

The following command was run before the fixes. Its output proves every new fixture fails under the old behavior:

```text
wrapped TODO: ['tracked-todo']
planner allow: ['planning-id']
ANSI-C shell: [] ['shell lexer ended inside a quote or heredoc']
```

After the fixes, the same fixture probe printed:

```text
wrapped TODO: []
planner allow: []
ANSI-C shell: ['after'] []
```

### Whole-tree policy check — confirmed by running it

Command:

```text
python3 scripts/verify-comments.py check
```

Output:

```text
OK: 290 files, 1089 comment blocks
```

### Equivalence check — confirmed by running it

The bare command specified in the brief does **not** exit zero, because it deliberately detects the two T5 executable gate changes and two previously accepted rollout YAML changes:

```text
python3 scripts/verify-comments.py equiv --base comment-pass-start
FAIL: equiv: .githooks/pre-commit: code differs (not a comment-only change)
FAIL: equiv: .github/workflows/invariant-checks.yml: code differs (not a comment-only change)
FAIL: equiv: k8s/monitoring/controllers/alloy.yaml: code differs (not a comment-only change)
FAIL: equiv: k8s/platform/traefik/helmchartconfig.yaml: code differs (not a comment-only change)
```

T5's plan explicitly prescribes exactly these four allow-listed files. The prescribed command exits zero:

```text
python3 scripts/verify-comments.py equiv --base comment-pass-start --allow .githooks/pre-commit --allow .github/workflows/invariant-checks.yml --allow k8s/monitoring/controllers/alloy.yaml --allow k8s/platform/traefik/helmchartconfig.yaml
NOTE: scripts/verify-comments-selftest.py is new since comment-pass-start, nothing to compare
NOTE: scripts/verify-comments.py is new since comment-pass-start, nothing to compare
OK: equiv vs comment-pass-start: 234 changed in-scope files equivalent, 2 new, 4 allow-listed
```

### Hook and CI wiring — reasoned about only

I did not execute the full hook because it invokes Docker/gitleaks, Gradle formatting, and fast tests before reaching this branch. Static verification confirms the hook resolves `python3`, then a Python-3 `python`, executes the checker with stdin redirected, refuses a failed policy check, and prints a loud warning if neither interpreter exists. It also confirms the CI job runs its self-test before the tree check:

```text
.githooks/pre-commit
124:COMMENT_PYTHON=""
126:  COMMENT_PYTHON=$(command -v python3)
128:  COMMENT_PYTHON=$(command -v python)
133:  "$COMMENT_PYTHON" scripts/verify-comments.py check < /dev/null
139:  echo "WARNING: Python 3 is unavailable; skipping code-comment policy check. CI comment-lint remains required."

.github/workflows/invariant-checks.yml
42:  comment-lint:
53:        run: python3 scripts/verify-comments-selftest.py
56:        run: python3 scripts/verify-comments.py check
```

`bash -n .githooks/pre-commit` also exited zero.

### Spotless — confirmed by running it

Command:

```text
./gradlew --no-daemon --max-workers=1 -Dorg.gradle.daemon.registry.base=/tmp/gd-t5 spotlessCheck
```

Output:

```text
> Task :spotlessInternalRegisterDependencies
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck

BUILD SUCCESSFUL in 19s
3 actionable tasks: 3 executed
```

### Full Gradle test suite — reasoned about only

Not run, as the brief explicitly says: “Do NOT run the full `./gradlew test`”.

No new lint exemptions were added. The equivalence allow-list is the planned four-file list above.

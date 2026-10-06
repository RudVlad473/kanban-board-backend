#!/usr/bin/env python3
"""Self-test for scripts/verify-instruction-budget.py: the gate fires above the ceiling and stays quiet at or below it.

A byte ceiling that never fires looks identical to a tree that is under it, so the gate would stay
green while capping nothing. Each case plants one oversized file, one lookalike that must not
count, or one violation in an in-memory fixture (a dict of repo-relative POSIX path to bytes) and
asserts the verdict. Only the last group reads the real checkout.
"""

import importlib.util
import io
import os
import sys
from contextlib import redirect_stderr, redirect_stdout

_GATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify-instruction-budget.py")
_spec = importlib.util.spec_from_file_location("verify_instruction_budget", _GATE_PATH)
_gate = importlib.util.module_from_spec(_spec)
sys.modules["verify_instruction_budget"] = _gate
_spec.loader.exec_module(_gate)

LIMIT = 1000
HUGE = 10**9


def manifest(files, tracked=None):
    tracked = set(files) if tracked is None else set(tracked)
    return _gate.build_manifest(tracked, lambda path: files.get(path))


def verdict(files, ceiling=LIMIT, tracked=None):
    return _gate.evaluate(manifest(files, tracked), ceiling)[0]


def paths(m):
    return sorted(row.path for row in m.rows)


def pad(size, head=b""):
    return head + b"x" * (size - len(head))


# ---------------------------------------------------------------- the ceiling itself


def test_over_ceiling_fails():
    assert not verdict({".claude/CLAUDE.md": pad(LIMIT + 1)})


def test_exactly_at_ceiling_passes():
    assert verdict({".claude/CLAUDE.md": pad(LIMIT)})


def test_one_below_ceiling_passes():
    assert verdict({".claude/CLAUDE.md": pad(LIMIT - 1)})


def test_total_sums_every_counted_file():
    files = {"CLAUDE.md": pad(600, b"@docs/a.md "), "docs/a.md": pad(600)}
    m = manifest(files)
    assert sum(row.size for row in m.rows) == 1200, m.rows
    assert not _gate.evaluate(m, LIMIT)[0]


# ---------------------------------------------------------------- roots


def test_each_root_name_fires_alone():
    names = list(_gate.ROOT_FILES) + [".claude/rules/x.md"]
    for expected in ("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md", "AGENTS.md", ".claude/AGENTS.md", "GEMINI.md"):
        assert expected in names, "root name %s is not in ROOT_FILES" % expected
    silent = [n for n in names if verdict({n: pad(LIMIT + 1)})]
    assert not silent, "these roots did not fire at ceiling+1: %s" % silent


def test_each_root_name_passes_at_ceiling():
    names = list(_gate.ROOT_FILES) + [".claude/rules/x.md"]
    loud = [n for n in names if not verdict({n: pad(LIMIT)})]
    assert not loud, "these roots fired at exactly the ceiling: %s" % loud


def test_rules_file_with_paths_frontmatter_is_conditional_and_not_counted():
    files = {
        ".claude/CLAUDE.md": b"hi\n",
        ".claude/rules/scoped.md": b"---\npaths:\n  - src/**\n---\n" + pad(LIMIT * 2),
    }
    m = manifest(files)
    assert paths(m) == [".claude/CLAUDE.md"], paths(m)
    assert any("scoped.md" in n and "conditional" in n for n in m.notes), m.notes
    assert _gate.evaluate(m, LIMIT)[0]


def test_rules_file_with_frontmatter_but_no_paths_key_is_counted():
    files = {".claude/rules/plain.md": b"---\ndescription: x\n---\n" + pad(LIMIT + 1)}
    assert not verdict(files)


def test_untracked_root_is_not_counted():
    files = {"CLAUDE.md": b"hi\n", ".claude/CLAUDE.md": pad(LIMIT * 2)}
    m = manifest(files, tracked={"CLAUDE.md"})
    assert paths(m) == ["CLAUDE.md"], paths(m)
    assert _gate.evaluate(m, LIMIT)[0]


def test_non_root_markdown_is_not_counted():
    files = {"CLAUDE.md": b"hi\n", "docs/big.md": pad(LIMIT * 2), "README.md": pad(LIMIT * 2)}
    assert paths(manifest(files)) == ["CLAUDE.md"]


# ---------------------------------------------------------------- imports


def test_import_resolves_against_the_importing_files_directory():
    files = {".claude/CLAUDE.md": b"see @../docs/big.md\n", "docs/big.md": pad(LIMIT)}
    m = manifest(files)
    assert paths(m) == [".claude/CLAUDE.md", "docs/big.md"], paths(m)
    assert not _gate.evaluate(m, LIMIT)[0]
    assert verdict({".claude/CLAUDE.md": b"see nothing\n", "docs/big.md": pad(LIMIT)})


def test_five_hop_chain_is_counted_and_the_sixth_is_not():
    files = {".claude/CLAUDE.md": b"@../docs/h1.md\n"}
    for n in range(1, 7):
        files["docs/h%d.md" % n] = ("@h%d.md\n" % (n + 1)).encode() if n < 6 else b"end\n"
    m = manifest(files)
    got = sorted((row.path, row.hop) for row in m.rows)
    want = [(".claude/CLAUDE.md", 0)] + [("docs/h%d.md" % n, n) for n in range(1, 6)]
    assert got == sorted(want), got


def test_import_cycle_counts_each_file_once():
    files = {"CLAUDE.md": b"@docs/b.md\n", "docs/b.md": b"@../CLAUDE.md\n"}
    m = manifest(files)
    assert paths(m) == ["CLAUDE.md", "docs/b.md"], paths(m)


def test_a_file_imported_twice_counts_once():
    files = {"CLAUDE.md": b"@docs/b.md and @docs/b.md\n", "docs/b.md": b"b\n"}
    assert paths(manifest(files)) == ["CLAUDE.md", "docs/b.md"]


def test_annotation_tokens_count_nothing_and_say_nothing():
    files = {"CLAUDE.md": b"Use @Service and @Transactional, plus @RestController.\n", "docs/Service.md": pad(LIMIT * 2)}
    m = manifest(files)
    assert paths(m) == ["CLAUDE.md"], paths(m)
    assert not m.violations and not m.notes, (m.violations, m.notes)


def test_import_inside_a_single_line_code_span_is_not_counted():
    files = {"CLAUDE.md": b"write `@docs/big.md` like so\n", "docs/big.md": pad(LIMIT * 2)}
    assert paths(manifest(files)) == ["CLAUDE.md"]


def test_import_inside_a_fenced_block_is_not_counted():
    files = {"CLAUDE.md": b"```\n@docs/big.md\n```\n~~~\n@docs/big.md\n~~~\n", "docs/big.md": pad(LIMIT * 2)}
    assert paths(manifest(files)) == ["CLAUDE.md"]


def test_email_shaped_token_is_not_an_import():
    files = {"CLAUDE.md": b"mail a@b.md now\n", "b.md": pad(LIMIT * 2)}
    assert paths(manifest(files)) == ["CLAUDE.md"]


def test_trailing_punctuation_is_stripped_before_resolving():
    files = {"CLAUDE.md": b"(see @docs/big.md).\n", "docs/big.md": pad(LIMIT)}
    assert paths(manifest(files)) == ["CLAUDE.md", "docs/big.md"]


def test_missing_path_like_import_is_noted_not_counted():
    files = {"CLAUDE.md": b"@docs/nope.md\n"}
    m = manifest(files)
    assert paths(m) == ["CLAUDE.md"] and not m.violations, (paths(m), m.violations)
    assert any("docs/nope.md" in n for n in m.notes), m.notes


def test_import_tokens_fence_length_and_character_are_tracked():
    text = "````md\n```\n@a.md\n```\n@b.md\n````\n@c.md\n"
    assert _gate.import_tokens(text) == ["c.md"], _gate.import_tokens(text)
    text = "~~~\n```\n@a.md\n~~~\n@d.md\n"
    assert _gate.import_tokens(text) == ["d.md"], _gate.import_tokens(text)


def test_import_tokens_scans_html_comments():
    assert _gate.import_tokens("<!-- @docs/a.md -->\n") == ["docs/a.md"]


# ---------------------------------------------------------------- violations


def test_home_absolute_and_escaping_imports_fail_even_under_the_ceiling():
    for token in ("~/.claude/x.md", "/etc/x.md", "../../outside.md"):
        files = {".claude/CLAUDE.md": ("@%s\n" % token).encode()}
        m = manifest(files)
        assert m.violations, "no violation recorded for @%s" % token
        assert not _gate.evaluate(m, HUGE)[0], "@%s passed under a huge ceiling" % token


def test_empty_manifest_fails():
    m = manifest({})
    assert not m.rows
    assert not _gate.evaluate(m, HUGE)[0]
    assert not verdict({"docs/x.md": b"x"})


# ---------------------------------------------------------------- the ratchet advisory


def test_ratchet_advisory_names_the_value_to_lower_to():
    text = _gate.ratchet_advisory(100, 5000)
    assert text and "500" in text, text


def test_ratchet_advisory_is_silent_within_the_advisory_margin():
    assert _gate.ratchet_advisory(4000, 5000) is None
    assert _gate.ratchet_advisory(3999, 5000) is not None


# ---------------------------------------------------------------- real checkout


def real_manifest():
    return _gate.build_manifest(_gate.tracked_paths(), _gate.read_working_tree)


def test_real_manifest_includes_the_project_instruction_file():
    m = real_manifest()
    assert ".claude/CLAUDE.md" in paths(m), paths(m)
    assert isinstance(_gate.CEILING_BYTES, int) and _gate.CEILING_BYTES > 0


def test_main_refuses_a_ceiling_looser_than_the_constant():
    sink = io.StringIO()
    with redirect_stdout(sink), redirect_stderr(sink):
        code = _gate.main(["--ceiling", str(_gate.CEILING_BYTES + 1)])
    assert code == 2, code


def test_main_fires_on_the_real_tree_with_a_tiny_ceiling():
    sink = io.StringIO()
    with redirect_stdout(sink), redirect_stderr(sink):
        code = _gate.main(["--ceiling", "1"])
    assert code == 1, code
    assert "OVER BUDGET" in sink.getvalue(), sink.getvalue()


def main():
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    failed = 0
    for name, func in tests:
        try:
            func()
            print("PASS: %s" % name)
        except AssertionError as err:
            failed += 1
            print("FAIL: %s: %s" % (name, err))
        except Exception as err:  # a crashing case must read as a failure, not a traceback that hides the rest
            failed += 1
            print("FAIL: %s: %s: %s" % (name, type(err).__name__, err))
    print("%d/%d passed" % (len(tests) - failed, len(tests)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

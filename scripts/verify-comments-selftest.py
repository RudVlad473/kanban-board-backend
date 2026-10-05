#!/usr/bin/env python3
"""Self-test for scripts/verify-comments.py: every extractor, equivalence check and gated rule can fire.

A rule or extractor that never fires on a planted violation is invisible against a tree that
already satisfies it, so the gate stays green while protecting nothing. Each case plants one
violation (or one lookalike that must be ignored) in an in-memory fixture and asserts the
verdict. Fixtures are in memory except two checks that read the real tree: the zero-files guard
and the PyYAML cross-check of the stdlib YAML comment detector (skipped when PyYAML is absent).
"""

import importlib.util
import os
import sys

_GATE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify-comments.py")
_spec = importlib.util.spec_from_file_location("verify_comments", _GATE_PATH)
_gate = importlib.util.module_from_spec(_spec)
sys.modules["verify_comments"] = _gate
_spec.loader.exec_module(_gate)

VIEW = _gate.View(paths={"docs/CODE_STYLE.md", "src/Real.java", ".planning/quick/x/y-SUMMARY.md"})
JAVA = "src/main/java/A.java"
TEST_JAVA = "src/test/java/ATest.java"
WORKFLOW = ".github/workflows/ci.yml"


def comment_texts(kind, text, workflow=False):
    return [t for ev in _gate.extract(kind, text, workflow) for _, t in ev.lines]


def rules(path, text):
    return sorted({v.rule for v in _gate.lint(path, text, VIEW).violations})


def suspects(path, text):
    return _gate.lint(path, text, VIEW).suspects


# ---------------------------------------------------------------- extractors


def test_java_real_comments_found():
    src = 'int a; // trailing\n/* block */\n/**\n * doc line\n */\nclass A {}\n'
    assert comment_texts("java", src) == ["trailing", "block", "", "doc line", ""], comment_texts("java", src)


def test_java_lookalikes_ignored():
    src = (
        'String a = "x // not";\n'
        'String b = "x /* not */";\n'
        "char c = '\"'; // real\n"
        'String d = """\n  // inside text block\n  /* also */\n""";\n'
        'String e = "esc \\" // still string";\n'
    )
    assert comment_texts("java", src) == ["real"], comment_texts("java", src)


def test_groovy_strings_ignored():
    src = "def a = 'x // not'\ndef b = '''\n// inside\n'''\ndef c = \"y // not\"\n// real\n"
    assert comment_texts("groovy", src) == ["real"], comment_texts("groovy", src)


def test_shell_comments_and_lookalikes():
    src = (
        "#!/bin/sh\n"
        "echo 'a # not' # real trailing\n"
        'echo "b # not"\n'
        "echo ${#arr} $#\n"
        "cat <<EOF\n# heredoc body\nEOF\n"
        "# real full line\n"
        "echo a#b\n"
    )
    got = comment_texts("shell", src)
    assert got == ["!/bin/sh", "real trailing", "real full line"], got


def test_shell_heredoc_dash_and_quoted_tag():
    src = "cat <<-'END'\n\t# body\n\tEND\n# after\n"
    assert comment_texts("shell", src) == ["after"], comment_texts("shell", src)


def test_yaml_comments_and_lookalikes():
    src = (
        "# top\n"
        "a: 1 # trailing\n"
        'b: "x # quoted"\n'
        "c: 'y # quoted'\n"
        "d: z#notcomment\n"
        "e: |\n"
        "  # block scalar content\n"
        "  more\n"
        "# after block\n"
        "f: it's fine # real\n"
    )
    got = comment_texts("yaml", src)
    assert got == ["top", "trailing", "after block", "real"], got


def test_yaml_workflow_run_block_hash_lines_are_shell_comments():
    src = "steps:\n  - run: |\n      echo hi\n      # shell comment\n      echo 'a # not' \n"
    assert comment_texts("yaml", src, workflow=True) == ["shell comment"]
    assert comment_texts("yaml", src, workflow=False) == []


def test_yaml_block_scalar_in_sequence_item_ends_at_sibling_key():
    src = "- run: |\n    body\n  name: x # real\n"
    assert comment_texts("yaml", src) == ["real"], comment_texts("yaml", src)


def test_sql_comments_and_lookalikes():
    src = (
        "select 1; -- real\n"
        "select '-- not', \"-- not\";\n"
        "/* block */\n"
        "do $$ begin -- inside dollar quote\n end $$;\n"
        "do $tag$ -- inside tagged\n$tag$;\n"
    )
    assert comment_texts("sql", src) == ["real", "block"], comment_texts("sql", src)


def test_properties_full_line_only():
    src = "# a\n! b\nkey=value # not a comment\n"
    assert comment_texts("properties", src) == ["a", "b"]


def test_toml_comments_and_lookalikes():
    src = 'a = "x # not"\nb = \'y # not\'\nc = """\n# inside\n"""\n# real\nd = 1 # trailing\n'
    assert comment_texts("toml", src) == ["real", "trailing"], comment_texts("toml", src)


def test_python_comments_and_docstrings():
    src = '"""Module doc."""\n# real\nx = "# not"\n\n\ndef f():\n    """Func doc."""\n    return 1\n'
    got = comment_texts("python", src)
    assert "Module doc." in got and "real" in got and "Func doc." in got and "# not" not in got, got


def test_line_family_full_line_hash():
    assert comment_texts("line", "# a\nKEY=v\n  # b\n") == ["a", "b"]


# ------------------------------------------------------------------ equiv

JAVA_OLD = 'class A {\n  // old note\n  int x = 1; // trailing\n  String s = "// keep";\n}\n'


def equiv(path, old, new, allow=False):
    return _gate.equiv_problems(path, old, new, allow)


def test_equiv_comment_only_edit_is_equivalent_in_every_family():
    cases = [
        (JAVA, JAVA_OLD, 'class A {\n  int x = 1;\n  String s = "// keep";\n}\n'),
        ("build.gradle", "// old\nplugins { id 'java' } // t\n", "plugins { id 'java' }\n"),
        ("s.sh", "#!/bin/sh\n# old\necho a # t\n", "#!/bin/sh\necho a\n"),
        ("q.sql", "-- old\nselect 1; -- t\n", "select 1;\n"),
        ("a.properties", "# old\nk=v\n", "k=v\n"),
        ("Dockerfile", "# old\nFROM x\n", "FROM x\n"),
        ("t.toml", "# old\na = 1 # t\n", "a = 1\n"),
        ("m.py", '"""Old."""\n# old\nx = 1\n', '"""New doc."""\nx = 1\n'),
        ("a.yaml", "# old\na: 1 # t\n", "a: 1\n"),
        (WORKFLOW, "a:\n  - run: |\n      # old\n      echo hi\n", "a:\n  - run: |\n      echo hi\n"),
    ]
    for path, old, new in cases:
        assert equiv(path, old, new) == [], (path, equiv(path, old, new))


def test_equiv_one_token_code_edit_is_not_equivalent_in_every_family():
    cases = [
        (JAVA, JAVA_OLD, JAVA_OLD.replace("= 1", "= 2")),
        ("build.gradle", "plugins { id 'java' }\n", "plugins { id 'jav' }\n"),
        ("s.sh", "echo a # t\n", "echo b # t\n"),
        ("q.sql", "select 1; -- t\n", "select 2; -- t\n"),
        ("a.properties", "k=v\n", "k=w\n"),
        ("Dockerfile", "FROM x\n", "FROM y\n"),
        ("t.toml", "a = 1\n", "a = 2\n"),
        ("m.py", "x = 1\n", "x = 2\n"),
        ("a.yaml", "a: 1\n", "a: 2\n"),
        (WORKFLOW, "a:\n  - run: |\n      echo hi\n", "a:\n  - run: |\n      echo ho\n"),
    ]
    for path, old, new in cases:
        assert equiv(path, old, new), path


def test_equiv_string_literal_change_is_not_equivalent():
    assert equiv(JAVA, JAVA_OLD, JAVA_OLD.replace('"// keep"', '"// keeq"'))


def test_equiv_deleted_functional_marker_is_not_equivalent():
    old = "images:\n  - newTag: x # {\"$imagepolicy\": \"a:b\"}\n"
    assert equiv("k8s/a.yaml", old, "images:\n  - newTag: x\n")
    sh_old = "# shellcheck disable=SC2086\necho $x\n"
    assert equiv("s.sh", sh_old, "echo $x\n")
    assert equiv("s.sh", "#!/bin/sh\necho a\n", "echo a\n")


def test_equiv_measured_keyword_may_be_reworded_but_not_dropped():
    old = "# MEASURED on 2026-09-01: 40Mi\nmemory: 64Mi\n"
    assert equiv("k8s/a.yaml", old, "# MEASURED 2026-09-01, 40Mi observed.\nmemory: 64Mi\n") == []
    assert equiv("k8s/a.yaml", old, "# observed 40Mi\nmemory: 64Mi\n")


def test_equiv_aaa_marker_count_must_match_in_tests():
    old = "class T {\n  void t() {\n    // arrange\n    int a = 1;\n    // act\n    // assert\n  }\n}\n"
    assert equiv(TEST_JAVA, old, old.replace("    // act\n", ""))
    assert equiv(TEST_JAVA, old, old.replace("// act", "// act now")) == []


def test_equiv_frozen_migration_refused_even_with_allow():
    path = "src/main/resources/db/migration/V1__init.sql"
    assert equiv(path, "-- a\nselect 1;\n", "-- b\nselect 1;\n", allow=True)
    assert equiv(path, "select 1;\n", "select 1;\n", allow=True) == []


def test_equiv_allow_skips_comparison_for_non_frozen():
    assert equiv("a.yaml", "a: 1\n", "a: 2\n", allow=True) == []


# ------------------------------------------------------------------ rules


def test_r1_planning_id_fires_on_each_gated_shape_and_not_on_clean_text():
    gated = [
        "// D-07: checked",
        "// quick task 260812-hs4 added this",
        "// Phase 13 moved it",
        "// per plan 13-04",
        "// SCHEMA-03 requires it",
        "// T-06-29 accepted",
        "// see 04-15-SUMMARY.md",
        "// Epic 2 work",
    ]
    for line in gated:
        assert rules(JAVA, line + "\nclass A {}\n") == ["planning-id"], line
    assert rules(JAVA, "// Keep the lock short.\nclass A {}\n") == []


def test_r1_existing_planning_path_masks_id_but_bare_artifact_name_is_flagged():
    ok = "// Evidence: .planning/quick/x/y-SUMMARY.md\nclass A {}\n"
    assert rules(JAVA, ok) == []
    bad = "// Evidence: y-SUMMARY.md\nclass A {}\n"
    assert rules(JAVA, bad) == ["planning-id"]
    dangling = "// Evidence: .planning/quick/gone/y-SUMMARY.md\nclass A {}\n"
    assert rules(JAVA, dangling) == ["planning-id"]


def test_r1_covers_every_requirement_prefix():
    for prefix in _gate.REQUIREMENT_PREFIXES:
        assert rules(JAVA, "// %s-01 here\nclass A {}\n" % prefix) == ["planning-id"], prefix


def test_r2_summary_first_fires_and_compliant_twin_passes():
    bad = "// one\n// two\n// three\n// four\nclass A {}\n"
    assert rules(JAVA, bad) == ["summary-first"]
    good = "// one\n//\n// two\n// three\n// four\nclass A {}\n"
    assert rules(JAVA, good) == []
    assert rules(JAVA, "// one\n// two\n// three\nclass A {}\n") == []


def test_r2_javadoc_tag_line_ends_the_summary_paragraph():
    bad = "/**\n * one\n * two\n * three\n * four\n */\nclass A {}\n"
    assert rules(JAVA, bad) == ["summary-first"]
    good = "/**\n * Summary.\n *\n * two\n * three\n * four\n * @param x y\n */\nclass A {}\n"
    assert rules(JAVA, good) == []


def test_r3_narration_needs_a_marker_after_eight_prose_lines():
    body = "".join("// line %d\n" % i for i in range(9))
    summary = "// Summary.\n//\n"
    assert rules(JAVA, summary + body + "class A {}\n") == ["segregated-narration"]
    for marker in ("Decisions:", "<p>Decisions:", "Known holes:", "Why this is the way it is:", "Decisions ───"):
        text = "/**\n * Summary.\n *\n" + "".join(" * line %d\n" % i for i in range(5)) + " * %s\n" % marker
        text += "".join(" * record %d\n" % i for i in range(20)) + " */\nclass A {}\n"
        assert rules(JAVA, text) == [], marker


def test_r3_param_return_throws_lines_are_not_prose():
    tags = "".join(" * @param p%d d\n" % i for i in range(12))
    text = "/**\n * Summary.\n *\n * detail\n *\n" + tags + " */\nclass A {}\n"
    assert rules(JAVA, text) == []


def test_r4_todo_needs_a_resolvable_target():
    assert rules(JAVA, "// TODO fix later\nclass A {}\n") == ["tracked-todo"]
    assert rules(JAVA, "// TODO: soon - fix\nclass A {}\n") == ["tracked-todo"]
    assert rules(JAVA, "// TODO: src/Gone.java - fix\nclass A {}\n") == ["tracked-todo"]
    assert rules(JAVA, "// TODO: https://example.com/1 - fix\nclass A {}\n") == []
    assert rules(JAVA, "// TODO: #42 - fix\nclass A {}\n") == []
    assert rules(JAVA, "// TODO: src/Real.java - fix\nclass A {}\n") == []
    for word in ("FIXME", "XXX", "HACK"):
        assert rules(JAVA, "// %s nope\nclass A {}\n" % word) == ["tracked-todo"], word


def test_suspects_are_reported_but_never_violations():
    res = _gate.lint(JAVA, "// moved in 13-06\n// Task 3 did it\n// see docs/Gone.md\nclass A {}\n", VIEW)
    assert res.violations == [], res.violations
    kinds = {s[0] for s in res.suspects}
    assert {"bare-plan-number", "task-n", "dangling-path"} <= kinds, kinds


def test_functional_lines_are_suppressed():
    text = (
        "#!/bin/sh\n"
        "# shellcheck disable=SC2086\n"
        "# noqa: D-07 style\n"
        "echo $x\n"
    )
    assert rules("s.sh", text) == []
    yaml = 'images:\n  - newTag: x # {"$imagepolicy": "flux-system:a:tag"} D-07\n'
    assert rules("k8s/o/kustomization.yaml", yaml) == []


def test_aaa_markers_are_not_prose():
    text = "class T {\n  void t() {\n    // arrange\n    // act\n    // assert\n  }\n}\n"
    assert rules(TEST_JAVA, text) == []


def test_exemption_classes_are_not_linted():
    bad = "-- D-07 old\nselect 1;\n"
    assert rules("src/main/resources/db/migration/V1__init.sql", bad) == []
    assert rules("k8s/data/postgres/init/01-x.sh", "#!/bin/sh\n# D-07\n") == []
    assert _gate.classify("gradlew") is None
    assert _gate.classify("gradle/wrapper/gradle-wrapper.properties") is None
    assert _gate.classify(".planning/x.sh") is None
    assert _gate.classify("docs/wiki/x.sql") is None
    assert _gate.classify("README.md") is None
    assert rules("k8s/o/kustomization.yaml", "# D-07\na: 1\n") == ["planning-id"]


def test_generated_flux_body_is_exempt_but_header_is_linted():
    path = "k8s/flux-system/gotk-components.yaml"
    body = "# header ok\n---\n# D-07 in generated body\na: 1\n"
    assert rules(path, body) == []
    header = "# D-07 in header\n---\na: 1\n"
    assert rules(path, header) == ["planning-id"]


def test_docstring_blocks_are_linted_in_python():
    bad = '"""Summary one.\nline two\nline three\nline four\n"""\n'
    assert rules("m.py", bad) == ["summary-first"]


# ------------------------------------------------------------ wiring


def test_zero_in_scope_files_is_a_failure_not_a_pass():
    assert _gate.cmd_check(["README.md"]) == 1


def test_pyyaml_crosscheck_of_stdlib_yaml_detector():
    try:
        import yaml
    except ImportError:
        print("SKIP: PyYAML not importable; stdlib YAML detector cross-check not run")
        return
    checked = 0
    for path in _gate.in_scope_paths():
        scope = _gate.classify(path)
        if scope is None or scope.kind != "yaml":
            continue
        text = _gate.read_text(path)
        if text is None:
            continue
        mine = {ev.start for ev in _gate.extract("yaml", text, False) if True}
        ref = _gate.yaml_scanner_comment_lines(text, yaml)
        assert mine == ref, "%s: stdlib-only lines %s, yaml.scan lines %s" % (
            path,
            sorted(mine - ref),
            sorted(ref - mine),
        )
        checked += 1
    assert checked > 0, "no YAML files cross-checked"
    print("  (cross-checked %d YAML files)" % checked)


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
    print("%d/%d passed" % (len(tests) - failed, len(tests)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

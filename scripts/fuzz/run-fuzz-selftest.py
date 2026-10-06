#!/usr/bin/env python3
"""Self-test for scripts/fuzz/run-fuzz.py: the loopback guard and the teardown can both fire.

A guard that never fires looks identical to one that works while every target happens to be local,
and a teardown that never runs looks identical to a clean machine. Each case here exercises one
direction of one safety property, so neutering the guard or deleting the teardown call turns this
file red. Nothing here starts Docker, Gradle or Schemathesis: process-level cases put fake docker
and uvx executables first on PATH and assert that they were never called.
"""

import importlib.util
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import time
from argparse import Namespace
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_SCRIPT = _HERE / "run-fuzz.py"
_spec = importlib.util.spec_from_file_location("run_fuzz", _SCRIPT)
fuzz = importlib.util.module_from_spec(_spec)
sys.modules["run_fuzz"] = fuzz
_spec.loader.exec_module(fuzz)

REFUSED_URLS = [
    "http://example.com/api",
    "http://10.0.0.5:9000/api",
    "http://localhost.example.com/api",
    "http://127.0.0.1.nip.io/api",
    "http://user@example.com/api",
    "http://localhost@example.com/api",
    "ftp://localhost/api",
    "http://0.0.0.0:9000/api",
    "",
]
ACCEPTED_URLS = [
    "http://localhost:9000/api",
    "http://127.0.0.1:9000/api",
    "http://[::1]:9000/api",
]


def free_port():
    return fuzz.pick_free_port()


# ---------------------------------------------------------------- loopback guard


def test_guard_refuses_every_non_loopback_target():
    for url in REFUSED_URLS:
        try:
            fuzz.assert_local_base_url(url)
        except fuzz.RefusedTarget as err:
            assert str(err).startswith("refusing"), "%r: message %r" % (url, str(err))
        else:
            raise AssertionError("guard accepted %r" % url)


def test_guard_accepts_loopback_targets():
    for url in ACCEPTED_URLS:
        try:
            fuzz.assert_local_base_url(url)
        except fuzz.RefusedTarget as err:
            raise AssertionError("guard refused %r: %s" % (url, err))


# ---------------------------------------------------------------- process level


class FakeTools:
    """Fake docker and uvx executables that record being called."""

    def __init__(self, directory):
        self.directory = Path(directory)
        self.markers = []
        for name in ("docker", "uvx"):
            marker = self.directory / (name + ".called")
            self.markers.append(marker)
            script = self.directory / name
            script.write_text('#!/bin/sh\ntouch "%s"\nexit 1\n' % marker)
            script.chmod(0o755)

    def called(self):
        return [m.name for m in self.markers if m.exists()]

    def env(self):
        env = dict(os.environ)
        env["PATH"] = str(self.directory) + os.pathsep + env.get("PATH", "")
        return env


def run_process(tools, base_url, report_dir):
    return subprocess.run(
        [sys.executable, str(_SCRIPT), "--base-url", base_url, "--report-dir", str(report_dir)],
        env=tools.env(),
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
        timeout=120,
    )


def test_process_refuses_a_remote_target_before_any_tool_starts():
    with tempfile.TemporaryDirectory() as tmp:
        tools = FakeTools(tmp)
        result = run_process(tools, "http://selftest.invalid:9/api", Path(tmp) / "report")
        assert result.returncode == 3, "exit %s, stderr %r" % (result.returncode, result.stderr)
        assert "refusing" in result.stderr, result.stderr
        assert tools.called() == [], "tools were started: %s" % tools.called()


def test_process_lets_a_loopback_target_through_the_guard():
    with tempfile.TemporaryDirectory() as tmp:
        tools = FakeTools(tmp)
        url = "http://127.0.0.1:%d/api" % free_port()
        result = run_process(tools, url, Path(tmp) / "report")
        assert result.returncode == 1, "exit %s, stderr %r" % (result.returncode, result.stderr)
        assert "refusing" not in result.stderr, result.stderr
        assert tools.called() == [], "tools were started: %s" % tools.called()
        meta = json.loads((Path(tmp) / "report" / "run.json").read_text())
        assert meta["boot_mode"] is False and meta["exit_code"] == 1, meta


# ---------------------------------------------------------------- teardown paths


class FakeSteps:
    """Injected steps: records every call and fails or returns on demand."""

    def __init__(self, boot=None, fuzz_result=0, teardown_checks=None):
        self.calls = []
        self._boot = boot
        self._fuzz = fuzz_result
        self._teardown_checks = {"clear": True} if teardown_checks is None else teardown_checks

    def preflight(self, ctx):
        self.calls.append("preflight")

    def boot(self, ctx):
        self.calls.append("boot")
        ctx.base_url = "http://127.0.0.1:9/api"
        if self._boot is not None:
            raise self._boot

    def prepare(self, ctx):
        self.calls.append("prepare")

    def fuzz(self, ctx):
        self.calls.append("fuzz")
        if isinstance(self._fuzz, BaseException):
            raise self._fuzz
        if callable(self._fuzz):
            return self._fuzz()
        return self._fuzz

    def teardown(self, ctx):
        self.calls.append("teardown")
        return self._teardown_checks


def drive(steps):
    with tempfile.TemporaryDirectory() as tmp:
        args = Namespace(
            base_url=None, max_examples=1, seed=1, refresh_seconds=10, report_dir=str(Path(tmp) / "r")
        )
        code = fuzz.run(args, steps)
    return code, steps.calls


def assert_torn_down_once(calls):
    assert calls.count("teardown") == 1, "teardown ran %d times: %s" % (calls.count("teardown"), calls)
    assert calls[-1] == "teardown", "teardown was not last: %s" % calls


def test_teardown_runs_when_the_fuzz_step_raises():
    code, calls = drive(FakeSteps(fuzz_result=RuntimeError("boom")))
    assert code == 1, code
    assert_torn_down_once(calls)


def test_teardown_runs_when_schemathesis_exits_2():
    code, calls = drive(FakeSteps(fuzz_result=2))
    assert code == 1, code
    assert_torn_down_once(calls)


def test_teardown_runs_when_boot_fails_midway():
    code, calls = drive(FakeSteps(boot=fuzz.HarnessError("compose failed")))
    assert code == 1, code
    assert "fuzz" not in calls, calls
    assert_torn_down_once(calls)


def test_teardown_runs_on_keyboard_interrupt():
    code, calls = drive(FakeSteps(fuzz_result=KeyboardInterrupt()))
    assert code == 1, code
    assert_torn_down_once(calls)


def test_teardown_runs_on_sigterm_through_the_installed_handler():
    code, calls = drive(FakeSteps(fuzz_result=lambda: os.kill(os.getpid(), signal.SIGTERM)))
    assert code == 1, code
    assert_torn_down_once(calls)


def test_sigterm_handler_raises_into_the_main_flow():
    try:
        fuzz._interrupt_handler(signal.SIGTERM, None)
    except KeyboardInterrupt:
        return
    raise AssertionError("the handler did not raise")


def test_findings_are_a_completed_run():
    for schemathesis_exit in (0, 1):
        code, calls = drive(FakeSteps(fuzz_result=schemathesis_exit))
        assert code == 0, "schemathesis exit %s gave %s" % (schemathesis_exit, code)
        assert_torn_down_once(calls)


def test_a_failed_teardown_check_fails_the_run():
    code, calls = drive(FakeSteps(fuzz_result=0, teardown_checks={"port_clear": False}))
    assert code == 1, code


def test_a_refused_target_during_the_run_exits_3():
    code, calls = drive(FakeSteps(fuzz_result=fuzz.RefusedTarget("refusing target")))
    assert code == 3, code
    assert_torn_down_once(calls)


# ---------------------------------------------------------------- environment and arguments


def test_child_env_strips_hostile_variables_and_sets_loopback_values():
    hostile = {
        "PATH": "/usr/bin",
        "HOME": "/home/x",
        "DB_HOST": "prod.example.com",
        "SPRING_DATASOURCE_URL": "jdbc:postgresql://prod.example.com/db",
        "SPRING_PROFILES_ACTIVE": "nonprod",
        "KAFKA_BOOTSTRAP_SERVERS": "broker.example.com:9092",
        "SCHEMA_REGISTRY_URL": "http://registry.example.com",
        "SERVER_PORT": "1",
        "MANAGEMENT_SERVER_PORT": "2",
        "JAVA_TOOL_OPTIONS": "-javaagent:/x.jar",
        "JDK_JAVA_OPTIONS": "-Dx=y",
    }
    env = fuzz.child_env(hostile, 4321, {"name": "n", "user": "u", "password": "p"})
    for gone in ("SPRING_DATASOURCE_URL", "SPRING_PROFILES_ACTIVE", "MANAGEMENT_SERVER_PORT",
                 "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):  # fmt: skip
        assert gone not in env, "%s survived" % gone
    assert env["DB_HOST"] == "localhost", env["DB_HOST"]
    assert env["KAFKA_BOOTSTRAP_SERVERS"] == "localhost:9092", env["KAFKA_BOOTSTRAP_SERVERS"]
    assert env["SCHEMA_REGISTRY_URL"] == "http://localhost:8081", env["SCHEMA_REGISTRY_URL"]
    assert env["SERVER_ADDRESS"] == "127.0.0.1" and env["SERVER_PORT"] == "4321", env
    assert env["PATH"] == "/usr/bin" and env["HOME"] == "/home/x", "unrelated variables were dropped"
    assert "SPRING_PROFILES_ACTIVE" not in env


def test_refresh_bound_comes_from_the_real_application_properties():
    props = fuzz.read_app_properties(fuzz.ROOT)
    max_age = props["cookie_max_age"]
    assert props["cookie_name"] and props["context_path"].startswith("/"), props
    for bad in (0, -1, max_age, max_age + 1):
        try:
            fuzz.validate_refresh(bad, max_age)
        except fuzz.HarnessError:
            continue
        raise AssertionError("refresh %s was accepted against max-age %s" % (bad, max_age))
    fuzz.validate_refresh(fuzz.DEFAULT_REFRESH_SECONDS, max_age)


def test_report_dir_must_be_ignored_or_outside_the_work_tree():
    fuzz.assert_report_dir_safe(fuzz.ROOT / "out" / "fuzz" / "x", fuzz.ROOT)
    try:
        fuzz.assert_report_dir_safe(fuzz.ROOT / "scripts" / "x", fuzz.ROOT)
    except fuzz.HarnessError:
        pass
    else:
        raise AssertionError("an un-ignored in-tree directory was accepted")
    with tempfile.TemporaryDirectory() as tmp:
        fuzz.assert_report_dir_safe(Path(tmp) / "report", fuzz.ROOT)


# ---------------------------------------------------------------- parsing


def test_ss_parser_returns_every_pid():
    line = 'LISTEN 0 100 127.0.0.1:1234 0.0.0.0:* users:(("java",pid=111,fd=5),("java",pid=222,fd=6))'
    assert fuzz.parse_ss_pids(line) == [111, 222], fuzz.parse_ss_pids(line)
    assert fuzz.parse_ss_pids("") == []


def test_summarize_groups_failures_by_class():
    doc = {
        "exit_code": 1,
        "running_time": 12.5,
        "operations": {"total": 24, "tested": 23},
        "test_cases": {"generated": 100, "unique_failures": 3},
        "failures": [
            {"title": "Response violates schema", "count": 2},
            {"title": "Server error", "count": 1},
            {"title": "Response violates schema", "count": 3},
        ],
        "errors": [{"title": "Schema error", "count": 4}],
        "auth": {"reauth_count": 2, "reauth_broke": False},
    }
    text = fuzz.summarize(doc)
    assert "| Response violates schema | 5 |" in text, text
    assert text.index("Response violates schema") < text.index("Server error"), text
    assert "| Schema error | 4 |" in text, text
    assert "operations tested: 23 of 24" in text and "unique failures: 3" in text, text


# ---------------------------------------------------------------- live kill-by-port


def wait_listening(port, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket() as sock:
            sock.settimeout(0.5)
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                return
        time.sleep(0.1)
    raise AssertionError("child never listened on %d" % port)


def test_stop_listener_spares_a_process_that_is_not_java_and_stops_a_matching_one():
    port = free_port()
    with tempfile.TemporaryDirectory() as tmp:
        child = subprocess.Popen(
            [sys.executable, "-m", "http.server", "--bind", "127.0.0.1", str(port)],
            cwd=tmp,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        try:
            wait_listening(port)
            matched, skipped = fuzz.stop_listener(port, grace=1.0)
            assert matched == [] and skipped == [child.pid], (matched, skipped, child.pid)
            assert child.poll() is None and fuzz.listener_pids(port) == [child.pid], "the bystander was stopped"
            matched, skipped = fuzz.stop_listener(port, expected="http.server", grace=10.0)
            assert matched == [child.pid], (matched, skipped)
            child.wait(timeout=10)
            assert fuzz.listener_pids(port) == [], "the port is still held"
        finally:
            if child.poll() is None:
                child.kill()
            child.wait()


def main():
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_") and callable(f)]
    failed = 0
    for name, func in tests:
        try:
            func()
            print("PASS: %s" % name)
        except Exception as err:
            failed += 1
            print("FAIL: %s: %s: %s" % (name, type(err).__name__, err))
    print("%d/%d passed" % (len(tests) - failed, len(tests)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

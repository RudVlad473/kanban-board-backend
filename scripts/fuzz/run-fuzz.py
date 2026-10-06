#!/usr/bin/env python3
"""Run a report-only Schemathesis fuzz pass against a throwaway local stack, then tear it down.

Boots an isolated compose project (Postgres and Redpanda), registers the Avro schemas, starts the
app on a free loopback port, signs up a random fuzz user and fuzzes the live OpenAPI document with
a pinned Schemathesis. Flags are documented in --help.
Exit codes: 0 the run completed, with or without findings; 1 harness failure; 3 refused target.

Decisions:
  * Kafka side: the stack is a throwaway compose Redpanda, and the schemas are registered with the
    build's registerSchemas task before the app starts. No toggle disables event publishing, and a
    dead broker would make every write wait out the producer block timeout. Registering the schemas
    also removes the serialization noise an empty registry produces.
  * Language: the re-sign-in must run inside Schemathesis' own Python process, so the hooks module
    is Python regardless. Orchestrator and hooks share one sign-in and one guard by importing this
    file, and the self-test imports it too.
  * Isolation: the compose project is named kanban-fuzz and is always torn down with its volumes,
    so teardown can never stop a developer's own dev stack, and fuzz data never lands in its
    database volume. The cost is that a running dev stack holding the same host ports blocks a run.
  * Session handling: every sign-in rotates the session id, which invalidates the previous cookie,
    so Schemathesis runs with one worker. Each re-sign-in carries the current cookie, so the server
    rotates the one session instead of opening a second; a third live session for one user is
    refused with 401.
  * The app is stopped by its listening port, because the JVM is a child of the Gradle daemon and
    outside this script's process group. The kill is limited to a process whose command line
    contains "java", so a reused port can never take down an unrelated process.
  * The pinned Schemathesis release and the dependency freeze date are named constants below. The
    freeze date needs a manual bump when upgrading.

Known holes:
  * A fixed seed fixes generation, not results: server-generated ids and session timing move the
    counts between runs, so one run is a sample, not a saturation figure.
  * The loopback check trusts the name localhost; a hosts file entry that points it elsewhere
    defeats it.
  * The port preflight checks the three compose host ports and cannot see a listener that appears
    between the check and the boot.
"""

import argparse
import datetime
import ipaddress
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

COMPOSE_PROJECT = "kanban-fuzz"
SCHEMATHESIS_VERSION = "4.29.3"
DEPENDENCY_FREEZE_DATE = "2026-10-06T00:00:00Z"
DEFAULT_SEED = 24925650107731152066331093038258341477
DEFAULT_MAX_EXAMPLES = 25
DEFAULT_REFRESH_SECONDS = 300
COMPOSE_HOST_PORTS = (5433, 9092, 8081)
SCHEMA_REGISTRY_URL = "http://localhost:8081"
BOOT_TIMEOUT_SECONDS = 300
FUZZ_TIMEOUT_SECONDS = 1500
ENV_PREFIXES_TO_STRIP = ("SPRING_", "DB_", "KAFKA_", "SCHEMA_REGISTRY_", "SERVER_", "MANAGEMENT_")
ENV_NAMES_TO_STRIP = ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS")
SIGN_UP_STATUS = 201
SIGN_IN_STATUS = 200


class RefusedTarget(Exception):
    """The target is not a loopback address."""


class HarnessError(Exception):
    """The run could not happen."""


class SignInFailed(HarnessError):
    def __init__(self, status, body):
        super().__init__("unexpected status %s: %s" % (status, body))
        self.status = status


class Interrupted(KeyboardInterrupt):
    """A termination signal turned into an exception on the main flow."""


def log(message):
    print("[fuzz] %s" % message, flush=True)


# ------------------------------------------------------------------ guard and environment


def assert_local_base_url(url):
    """Accept only an http(s) URL whose host is exactly localhost or a loopback IP literal."""
    if not isinstance(url, str) or not url.strip():
        raise RefusedTarget("refusing target: empty URL")
    if any(ch.isspace() or ch == "\\" or ord(ch) < 32 for ch in url):
        raise RefusedTarget("refusing target %r: whitespace or backslash in URL" % url)
    try:
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname
        _ = parts.port
    except ValueError as err:
        raise RefusedTarget("refusing target %r: unparsable (%s)" % (url, err))
    if parts.scheme not in ("http", "https"):
        raise RefusedTarget("refusing target %r: scheme must be http or https" % url)
    if parts.username is not None or parts.password is not None:
        raise RefusedTarget("refusing target %r: credentials in the URL" % url)
    if not host:
        raise RefusedTarget("refusing target %r: no host" % url)
    if host == "localhost":
        return
    try:
        loopback = ipaddress.ip_address(host).is_loopback
    except ValueError:
        loopback = False
    if not loopback:
        raise RefusedTarget("refusing target %r: host %r is not localhost or a loopback address" % (url, host))


def read_app_properties(root):
    """Read the context path and session cookie settings the sign-in code depends on."""
    wanted = {
        "server.servlet.context-path": "context_path",
        "server.servlet.session.cookie.name": "cookie_name",
        "server.servlet.session.cookie.max-age": "cookie_max_age",
    }
    found = {}
    path = Path(root) / "src" / "main" / "resources" / "application.properties"
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        if key in wanted:
            found[wanted[key]] = value.strip()
    missing = sorted(set(wanted.values()) - set(found))
    if missing:
        raise HarnessError("application.properties lacks %s" % ", ".join(missing))
    try:
        found["cookie_max_age"] = int(found["cookie_max_age"])
    except ValueError:
        raise HarnessError("cookie max-age is not an integer: %r" % found["cookie_max_age"])
    return found


def validate_refresh(seconds, cookie_max_age):
    if not 1 <= seconds < cookie_max_age:
        raise HarnessError(
            "--refresh-seconds %s must be at least 1 and below the session cookie max-age %s"
            % (seconds, cookie_max_age)
        )


def pick_free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def child_env(base_env, port, db):
    """Return a copy of base_env with every inherited datasource, broker and server setting removed."""
    env = {
        key: value
        for key, value in base_env.items()
        if not key.startswith(ENV_PREFIXES_TO_STRIP) and key not in ENV_NAMES_TO_STRIP
    }
    env.update(
        {
            "DB_HOST": "localhost",
            "DB_PORT": "5433",
            "DB_NAME": db["name"],
            "DB_USER": db["user"],
            "DB_PASS": db["password"],
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "SCHEMA_REGISTRY_URL": SCHEMA_REGISTRY_URL,
            "SPRING_JPA_HIBERNATE_DDL_AUTO": "validate",
            "SERVER_PORT": str(port),
            "SERVER_ADDRESS": "127.0.0.1",
        }
    )
    return env


def assert_report_dir_safe(path, root):
    """Refuse an in-tree report directory that git does not ignore."""
    resolved = Path(path).resolve()
    root = Path(root).resolve()
    try:
        resolved.relative_to(root)
    except ValueError:
        return
    result = subprocess.run(
        ["git", "-C", str(root), "check-ignore", "-q", str(resolved)],
        stdin=subprocess.DEVNULL,
        capture_output=True,
    )
    if result.returncode != 0:
        raise HarnessError("report directory %s is inside the work tree and not git-ignored" % resolved)


def parse_ss_pids(text):
    return [int(pid) for pid in re.findall(r"pid=(\d+)", text)]


def listener_pids(port):
    result = subprocess.run(
        ["ss", "-ltnpH", "sport = :%d" % port],
        stdin=subprocess.DEVNULL,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise HarnessError("ss failed: %s" % result.stderr.strip())
    return sorted(set(parse_ss_pids(result.stdout)))


def _cmdline(pid):
    try:
        raw = Path("/proc/%d/cmdline" % pid).read_bytes()
    except OSError:
        return ""
    return raw.replace(b"\0", b" ").decode(errors="replace")


def stop_listener(port, expected="java", grace=20.0):
    """Stop the processes listening on port whose command line contains expected; leave the rest."""
    matched, skipped = [], []
    for pid in listener_pids(port):
        (matched if expected in _cmdline(pid) else skipped).append(pid)
    for pid in matched:
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    deadline = time.monotonic() + grace
    while matched and time.monotonic() < deadline:
        if not set(matched) & set(listener_pids(port)):
            break
        time.sleep(0.25)
    for pid in set(matched) & set(listener_pids(port)):
        try:
            os.kill(pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    if matched:
        time.sleep(0.25)
    return matched, skipped


# ------------------------------------------------------------------ sign-in


def fuzz_credentials():
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    email = "fuzz-%s-%s@example.com" % (stamp, secrets.token_hex(4))
    password = secrets.token_urlsafe(18) + "aZ9!"
    return email, "Fuzz", password


def _opener():
    return urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _cookie_from(headers, cookie_name):
    found = None
    for header in headers.get_all("Set-Cookie") or []:
        name, _, rest = header.split(";", 1)[0].partition("=")
        if name.strip() == cookie_name and rest:
            found = rest
    return found


def _post_json(url, payload, cookie, cookie_name, expected_status):
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if cookie:
        headers["Cookie"] = "%s=%s" % (cookie_name, cookie)
    request = urllib.request.Request(url, data=json.dumps(payload).encode(), headers=headers, method="POST")
    try:
        with _opener().open(request, timeout=30) as response:
            status, body, resp_headers = response.status, response.read(), response.headers
    except urllib.error.HTTPError as err:
        status, body, resp_headers = err.code, err.read(), err.headers
    except urllib.error.URLError as err:
        raise HarnessError("%s unreachable: %s" % (url, err.reason))
    if status != expected_status:
        raise SignInFailed(status, body[:200].decode(errors="replace"))
    return status, _cookie_from(resp_headers, cookie_name) or cookie


def sign_up(base, email, display_name, password, cookie_name="JSESSIONID"):
    payload = {"email": email, "displayName": display_name, "password": password}
    return _post_json(base + "/signup", payload, None, cookie_name, SIGN_UP_STATUS)


def sign_in(base, email, password, cookie=None, cookie_name="JSESSIONID"):
    """Sign in, carrying cookie when given so the server rotates that session instead of opening one."""
    payload = {"email": email, "password": password}
    return _post_json(base + "/signin", payload, cookie, cookie_name, SIGN_IN_STATUS)


def http_get(url, timeout=10):
    try:
        with _opener().open(url, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as err:
        return err.code, err.read()
    except (urllib.error.URLError, OSError) as err:
        raise HarnessError("%s unreachable: %s" % (url, err))


# ------------------------------------------------------------------ report


def summarize(doc):
    """Render the by-class failure table and totals from a Schemathesis JSON report."""
    operations = doc.get("operations", {})
    cases = doc.get("test_cases", {})
    auth = doc.get("auth", {})

    def grouped(entries):
        counts = {}
        for entry in entries:
            title = entry.get("title", "(untitled)")
            counts[title] = counts.get(title, 0) + int(entry.get("count", 0))
        return sorted(counts.items(), key=lambda item: (-item[1], item[0]))

    lines = ["## Failures by class", "", "| Failure | Count |", "|---|---|"]
    rows = grouped(doc.get("failures", []))
    lines += ["| %s | %d |" % row for row in rows] or ["| (none) | 0 |"]
    lines += ["", "## Errors", "", "| Error | Count |", "|---|---|"]
    rows = grouped(doc.get("errors", []))
    lines += ["| %s | %d |" % row for row in rows] or ["| (none) | 0 |"]
    lines += [
        "",
        "## Totals",
        "",
        "- operations tested: %s of %s" % (operations.get("tested"), operations.get("total")),
        "- cases generated: %s" % cases.get("generated"),
        "- unique failures: %s" % cases.get("unique_failures"),
        "- schemathesis exit code: %s" % doc.get("exit_code"),
        "- running time: %s s" % doc.get("running_time"),
        "- re-authentications: %s (broke: %s)" % (auth.get("reauth_count"), auth.get("reauth_broke")),
        "",
    ]
    return "\n".join(lines)


def tail(path, lines=30):
    try:
        return "\n".join(Path(path).read_text(errors="replace").splitlines()[-lines:])
    except OSError:
        return "(no log)"


# ------------------------------------------------------------------ process helpers


def kill_group(proc, grace=15.0):
    if proc.poll() is not None:
        return
    try:
        os.killpg(proc.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        proc.wait(timeout=grace)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        proc.wait()


def run_logged(cmd, log_path, env, cwd, timeout):
    with open(log_path, "ab") as handle:
        proc = subprocess.Popen(
            cmd,
            cwd=str(cwd),
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            return proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            kill_group(proc)
            raise HarnessError("%s timed out after %s s" % (cmd[0], timeout))
        except BaseException:
            kill_group(proc)
            raise


def port_is_free(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind(("0.0.0.0", port))
        except OSError:
            return False
    return True


# ------------------------------------------------------------------ the run


class Context:
    def __init__(self, args):
        self.args = args
        self.base_url = args.base_url
        self.boot_mode = args.base_url is None
        self.report_dir = Path(args.report_dir)
        self.props = None
        self.env = None
        self.db = None
        self.app_port = None
        self.app_proc = None
        self.compose_started = False
        self.email = self.display_name = self.password = self.cookie = None
        self.schemathesis_exit = None
        self.timings = {}
        self.teardown_checks = None


def _interrupt_handler(signum, frame):
    raise Interrupted("signal %d" % signum)


class RealSteps:
    def _path(self, ctx, name):
        return ctx.report_dir / name

    def _compose(self, ctx, *args, log_name=None, timeout=600):
        cmd = [
            "docker", "compose", "-p", COMPOSE_PROJECT,
            "-f", str(ROOT / "docker-compose.yml"), "--project-directory", str(ROOT),
        ] + list(args)  # fmt: skip
        return run_logged(cmd, self._path(ctx, log_name or "compose.log"), ctx.env, ROOT, timeout)

    def preflight(self, ctx):
        if not ctx.boot_mode:
            if not shutil.which("uvx"):
                raise HarnessError("uvx is not on PATH")
            return
        for tool in ("docker", "uvx", "ss", "git"):
            if not shutil.which(tool):
                raise HarnessError("%s is not on PATH" % tool)
        info = subprocess.run(["docker", "info"], stdin=subprocess.DEVNULL, capture_output=True)
        if info.returncode != 0:
            raise HarnessError("docker is not reachable: %s" % info.stderr.decode(errors="replace").strip()[:200])
        for port in COMPOSE_HOST_PORTS:
            if not port_is_free(port):
                raise HarnessError(
                    "host port %d is busy; stop the local dev stack (docker compose down) and rerun" % port
                )

    def boot(self, ctx):
        if not ctx.boot_mode:
            return
        ctx.db = {"name": "kanban_fuzz", "user": "kanban_fuzz", "password": secrets.token_hex(12)}
        ctx.app_port = pick_free_port()
        ctx.env = child_env(os.environ, ctx.app_port, ctx.db)
        ctx.base_url = "http://127.0.0.1:%d%s" % (ctx.app_port, ctx.props["context_path"])
        log("compose project %s, app port %d" % (COMPOSE_PROJECT, ctx.app_port))

        ctx.compose_started = True
        if self._compose(ctx, "up", "-d", "--wait", "postgres", "redpanda") != 0:
            raise HarnessError("compose up failed:\n" + tail(self._path(ctx, "compose.log")))
        deadline = time.monotonic() + 120
        while True:
            ready = self._compose(
                ctx, "exec", "-T", "postgres", "pg_isready", "-U", ctx.db["user"], "-d", ctx.db["name"],
                log_name="pg-ready.log", timeout=30,
            )  # fmt: skip
            if ready == 0:
                break
            if time.monotonic() > deadline:
                raise HarnessError("postgres did not become ready within 120 s")
            time.sleep(2)

        log("registering Avro schemas")
        rc = run_logged(
            ["./gradlew", "--console=plain", "registerSchemas", "-PschemaRegistryUrl=" + SCHEMA_REGISTRY_URL],
            self._path(ctx, "register-schemas.log"), ctx.env, ROOT, 900,
        )  # fmt: skip
        if rc != 0:
            raise HarnessError("registerSchemas failed:\n" + tail(self._path(ctx, "register-schemas.log")))

        log("starting the app")
        handle = open(self._path(ctx, "app.log"), "ab")
        try:
            ctx.app_proc = subprocess.Popen(
                ["./gradlew", "--console=plain", "bootRun"],
                cwd=str(ROOT), env=ctx.env, stdin=subprocess.DEVNULL,
                stdout=handle, stderr=subprocess.STDOUT, start_new_session=True,
            )  # fmt: skip
        finally:
            handle.close()
        deadline = time.monotonic() + BOOT_TIMEOUT_SECONDS
        while True:
            if ctx.app_proc.poll() is not None:
                raise HarnessError("the app exited during boot:\n" + tail(self._path(ctx, "app.log")))
            try:
                if http_get(ctx.base_url + "/docs", timeout=5)[0] == 200:
                    return
            except HarnessError:
                pass
            if time.monotonic() > deadline:
                raise HarnessError("the app did not answer within %d s:\n%s" % (BOOT_TIMEOUT_SECONDS, tail(self._path(ctx, "app.log"))))
            time.sleep(2)

    def prepare(self, ctx):
        name = ctx.props["cookie_name"]
        status, body = http_get(ctx.base_url + "/docs")
        if status != 200:
            raise HarnessError("GET /docs returned %s" % status)
        ctx.email, ctx.display_name, ctx.password = fuzz_credentials()
        _, cookie = sign_up(ctx.base_url, ctx.email, ctx.display_name, ctx.password, name)
        _, ctx.cookie = sign_in(ctx.base_url, ctx.email, ctx.password, cookie, name)
        status, body = http_get(ctx.base_url + "/docs")
        if status != 200:
            raise HarnessError("GET /docs returned %s" % status)
        self._path(ctx, "openapi.json").write_bytes(body)

    def fuzz(self, ctx):
        args = ctx.args
        env = dict(os.environ)
        env.update(
            {
                "FUZZ_BASE_URL": ctx.base_url,
                "FUZZ_EMAIL": ctx.email,
                "FUZZ_PASSWORD": ctx.password,
                "FUZZ_SESSION_COOKIE": ctx.cookie or "",
                "FUZZ_COOKIE_NAME": ctx.props["cookie_name"],
                "FUZZ_REFRESH_SECONDS": str(args.refresh_seconds),
                "FUZZ_REPORT_DIR": str(ctx.report_dir),
                "SCHEMATHESIS_HOOKS": str(Path(__file__).resolve().with_name("schemathesis-hooks.py")),
            }
        )
        cmd = [
            "uvx", "--exclude-newer", DEPENDENCY_FREEZE_DATE, "--from", "schemathesis==" + SCHEMATHESIS_VERSION,
            "schemathesis", "run", str(self._path(ctx, "openapi.json")), "--url", ctx.base_url,
            "--workers", "1", "--max-examples", str(args.max_examples), "--seed", str(args.seed),
            "--report", "json,junit",
            "--report-json-path", str(self._path(ctx, "schemathesis.json")),
            "--report-junit-path", str(self._path(ctx, "junit.xml")),
        ]  # fmt: skip
        log("running Schemathesis %s, seed %s, %s examples" % (SCHEMATHESIS_VERSION, args.seed, args.max_examples))
        return run_logged(cmd, self._path(ctx, "schemathesis.log"), env, ctx.report_dir, FUZZ_TIMEOUT_SECONDS)

    def teardown(self, ctx):
        """Stop what this run started and verify nothing is left. Does nothing in --base-url mode."""
        checks = {}
        if not ctx.boot_mode:
            return checks
        if ctx.app_port is not None and ctx.app_proc is not None:
            matched, skipped = stop_listener(ctx.app_port)
            if skipped:
                log("left alone: pids %s listen on the app port but are not java" % skipped)
        if ctx.app_proc is not None:
            kill_group(ctx.app_proc)
        if ctx.compose_started:
            self._compose(ctx, "logs", "--no-color", log_name="compose.log", timeout=120)
            self._compose(ctx, "down", "-v", "--remove-orphans", log_name="compose-down.log", timeout=300)
        label = "label=com.docker.compose.project=" + COMPOSE_PROJECT
        for key, cmd in (
            ("containers_clear", ["docker", "ps", "-aq", "--filter", label]),
            ("volumes_clear", ["docker", "volume", "ls", "-q", "--filter", label]),
        ):
            result = subprocess.run(cmd, stdin=subprocess.DEVNULL, capture_output=True, text=True)
            checks[key] = result.returncode == 0 and not result.stdout.strip()
        if ctx.app_port is not None:
            checks["port_clear"] = not listener_pids(ctx.app_port)
        return checks


def run(args, steps, ctx=None):
    """Drive one run. steps supplies preflight, boot, prepare, fuzz and teardown."""
    ctx = ctx or Context(args)
    started = time.monotonic()
    code = 1
    previous = {}
    for sig in (signal.SIGTERM, signal.SIGINT):
        previous[sig] = signal.signal(sig, _interrupt_handler)
    try:
        steps.preflight(ctx)
        steps.boot(ctx)
        steps.prepare(ctx)
        assert_local_base_url(ctx.base_url)
        ctx.schemathesis_exit = steps.fuzz(ctx)
        if ctx.schemathesis_exit in (0, 1):
            code = 0
        else:
            log("Schemathesis exited %s, which is a harness failure" % ctx.schemathesis_exit)
    except RefusedTarget as err:
        print(str(err), file=sys.stderr)
        code = 3
    except KeyboardInterrupt as err:
        log("interrupted (%s)" % err)
    except Exception as err:
        log("harness failure: %s" % err)
    finally:
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, signal.SIG_IGN)
        checks = None
        try:
            checks = steps.teardown(ctx)
        except BaseException as err:
            log("teardown failed: %s" % err)
        for sig, handler in previous.items():
            signal.signal(sig, handler if handler is not None else signal.SIG_DFL)
    if checks is None or not all(checks.values()):
        log("teardown verification failed: %s" % checks)
        code = 1 if code == 0 else code
    ctx.timings["total_seconds"] = round(time.monotonic() - started, 1)
    ctx.teardown_checks = checks
    return finish(ctx, code)


def finish(ctx, code):
    """Write run.json and summary.md; a failure to write them is a harness failure."""
    try:
        args = ctx.args
        report_dir = Path(ctx.report_dir)
        report_dir.mkdir(parents=True, exist_ok=True)
        meta = {
            "schemathesis": SCHEMATHESIS_VERSION,
            "dependency_freeze": DEPENDENCY_FREEZE_DATE,
            "seed": args.seed,
            "max_examples": args.max_examples,
            "refresh_seconds": args.refresh_seconds,
            "app_port": ctx.app_port,
            "boot_mode": ctx.boot_mode,
            "schemathesis_exit": ctx.schemathesis_exit,
            "teardown": ctx.teardown_checks,
            "timings": ctx.timings,
            "exit_code": code,
        }
        (report_dir / "run.json").write_text(json.dumps(meta, indent=2) + "\n")
        summary = "# API fuzz run\n\n"
        try:
            doc = json.loads((report_dir / "schemathesis.json").read_text())
            summary += summarize(doc)
        except (OSError, ValueError):
            summary += "No Schemathesis report was produced.\n"
        summary += "\nTeardown checks: %s\n" % json.dumps(ctx.teardown_checks)
        (report_dir / "summary.md").write_text(summary)
        step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
        if step_summary:
            with open(step_summary, "a") as handle:
                handle.write(summary)
    except (OSError, ValueError, AttributeError) as err:
        log("could not write the run summary: %s" % err)
        return 1 if code == 0 else code
    return code


def parse_args(argv):
    parser = argparse.ArgumentParser(description="Report-only Schemathesis fuzz run against a throwaway local stack.")
    parser.add_argument(
        "--base-url",
        default=None,
        help="fuzz an already running local app instead of booting one; boots and stops nothing, "
        "and accepts only loopback targets",
    )
    parser.add_argument("--max-examples", type=int, default=DEFAULT_MAX_EXAMPLES, help="examples per operation")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED, help="Schemathesis seed")
    parser.add_argument(
        "--refresh-seconds",
        type=int,
        default=DEFAULT_REFRESH_SECONDS,
        help="re-sign-in interval; must be below the session cookie max-age in application.properties",
    )
    parser.add_argument(
        "--report-dir",
        default=None,
        help="output directory, git-ignored or outside the work tree (default out/fuzz/<UTC timestamp>); "
        "holds openapi.json, schemathesis.json, junit.xml, schemathesis.log, app.log, "
        "register-schemas.log, compose.log, signins.log, run.json and summary.md",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        if args.base_url is not None:
            assert_local_base_url(args.base_url)
    except RefusedTarget as err:
        print(str(err), file=sys.stderr)
        return 3
    try:
        props = read_app_properties(ROOT)
        validate_refresh(args.refresh_seconds, props["cookie_max_age"])
        if args.report_dir:
            report_dir = ROOT / args.report_dir
        else:
            stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            report_dir = ROOT / "out" / "fuzz" / stamp
        assert_report_dir_safe(report_dir, ROOT)
        report_dir.mkdir(parents=True, exist_ok=True)
    except HarnessError as err:
        print("harness failure: %s" % err, file=sys.stderr)
        return 1
    args.report_dir = str(report_dir)
    ctx = Context(args)
    ctx.props = props
    return run(args, RealSteps(), ctx)


if __name__ == "__main__":
    sys.exit(main())

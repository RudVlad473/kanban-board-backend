"""Schemathesis hooks: keep one signed-in session alive for the whole fuzz run.

Loaded through the SCHEMATHESIS_HOOKS environment variable that run-fuzz.py sets. Reads FUZZ_BASE_URL,
FUZZ_EMAIL, FUZZ_PASSWORD, FUZZ_SESSION_COOKIE, FUZZ_COOKIE_NAME, FUZZ_REFRESH_SECONDS and
FUZZ_REPORT_DIR from the environment, so no credential appears in a command line or a report.

Decisions:
  * The provider re-signs in on a timer and, through the library's own retry on 401, whenever the
    server drops the session. Each sign-in carries the current cookie, so the server rotates the one
    session instead of opening another; a third live session for one user is refused.
  * The provider skips the signin and signup operations. A cookie sent with a fuzzed signup would
    re-authenticate the shared session as a junk user.
  * The sign-in and the loopback guard are imported from run-fuzz.py so each exists once.
"""

import datetime
import importlib.util
import os
from pathlib import Path

import schemathesis

_ORCHESTRATOR = Path(__file__).resolve().with_name("run-fuzz.py")
_spec = importlib.util.spec_from_file_location("run_fuzz", _ORCHESTRATOR)
_fuzz = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_fuzz)

BASE_URL = os.environ["FUZZ_BASE_URL"]
_fuzz.assert_local_base_url(BASE_URL)

EMAIL = os.environ["FUZZ_EMAIL"]
PASSWORD = os.environ["FUZZ_PASSWORD"]
COOKIE_NAME = os.environ.get("FUZZ_COOKIE_NAME", "JSESSIONID")
REFRESH_SECONDS = int(os.environ["FUZZ_REFRESH_SECONDS"])
SIGNIN_LOG = Path(os.environ["FUZZ_REPORT_DIR"]) / "signins.log"


def _record(status):
    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")
    with open(SIGNIN_LOG, "a", encoding="utf-8") as handle:
        handle.write("%s status=%s\n" % (stamp, status))


@schemathesis.auth(refresh_interval=REFRESH_SECONDS).skip_for(path=["/signin", "/signup"])
class SessionCookieAuth:
    def __init__(self):
        self.cookie = os.environ.get("FUZZ_SESSION_COOKIE") or None

    def get(self, case, ctx):
        try:
            status, self.cookie = _fuzz.sign_in(BASE_URL, EMAIL, PASSWORD, self.cookie, COOKIE_NAME)
        except _fuzz.SignInFailed as err:
            _record(err.status)
            raise
        _record(status)
        return self.cookie

    def set(self, case, data, ctx):
        case.headers = case.headers or {}
        case.headers["Cookie"] = "%s=%s" % (COOKIE_NAME, data)

#!/usr/bin/env python3
"""Vanilla-upstream route-surface contract check (ADR 34).

Verifies that the routes the Android *standard path* (no-plugin) depends on are
actually declared by **unmodified** upstream hermes-agent — and that we are
checking vanilla source, not our relay fork.

Why source-parse instead of a live HTTP probe? Booting both upstream servers
headless (the aiohttp API server *and* the FastAPI dashboard) needs config, a
provider, and a DB, and most routes can't be reached without auth anyway. The
route *surface* — which is the thing that drifts when upstream renames or drops
an endpoint — is declared as literal strings:

    api_server (aiohttp):   self._app.router.add_get("/v1/capabilities", ...)
    dashboard  (FastAPI):   @app.post("/api/audio/transcribe")  /  @app.websocket("/api/ws")

so parsing them is deterministic, dependency-free, framework-agnostic, and
needs no model keys. The tradeoff (documented): this catches renamed/removed
routes but not runtime-auth regressions. A live-HTTP existence probe
(404 = fail; 401/400/405 = pass) is the richer future variant.

Usage:
    python scripts/check-upstream-route-contract.py <upstream_repo_root>
    UPSTREAM_DIR=/path/to/hermes-agent python scripts/check-upstream-route-contract.py

Exit codes: 0 = all REQUIRED routes present; 1 = a REQUIRED route is missing or
a source file/structure check failed.
"""
from __future__ import annotations

import os
import re
import sys
from pathlib import Path

API_SERVER = "gateway/platforms/api_server.py"
WEB_SERVER = "hermes_cli/web_server.py"

# aiohttp:  .router.add_get("/path"   /   .add_post('/path'
_AIOHTTP_RE = re.compile(
    r"""\.(?:router\.)?add_(get|post|patch|delete|put|head|route)\(\s*["']([^"']+)["']"""
)
# FastAPI/Starlette decorators:  @app.get("/path")  @app.websocket("/path")
_FASTAPI_RE = re.compile(
    r"""@\w+\.(get|post|patch|delete|put|head|websocket)\(\s*["']([^"']+)["']"""
)

# Routes the standard (no-plugin) path hard-depends on. Missing => build fails.
REQUIRED = {
    # api_server (aiohttp) — chat transports, discovery, health
    "/v1/capabilities",
    "/v1/chat/completions",
    "/v1/runs",
    "/v1/runs/{run_id}/events",
    "/api/sessions",
    "/api/sessions/{session_id}/messages",
    "/api/sessions/{session_id}/chat/stream",
    "/health",
    # dashboard (FastAPI) — Manage, standard voice, gateway chat transport
    "/api/status",
    "/api/audio/transcribe",
    "/api/audio/speak",
    "/api/ws",
}

# Mode-dependent / optional. Missing => warn only (the app degrades). Tracks the
# routes whose presence varies by upstream version or loopback-vs-remote mode.
ADVISORY = {
    "/v1/models",
    "/v1/skills",
    "/v1/toolsets",
    "/api/pty",
    # Remote dashboard auth-gate (absent in loopback-token builds; the app falls
    # back to the injected session token + ws `ticket` query param).
    "/api/auth/ws-ticket",
    "/api/auth/me",
    "/auth/password-login",
}

# If api_server.py contains any of these, we are NOT looking at vanilla upstream
# (it's our relay fork with routes compiled in) — which would invalidate the
# whole point of the check. See memory: hermes-fork-axiom-contract-runtime.
FORK_MARKERS = ("hermes_relay", "/pairing/register", "RelayPlugin", "hermes-relay")


def extract_routes(path: Path, pattern: re.Pattern) -> set[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    return {m.group(2) for m in pattern.finditer(text)}


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("UPSTREAM_DIR", ".")).resolve()
    api_path = root / API_SERVER
    web_path = root / WEB_SERVER

    problems: list[str] = []
    for p in (api_path, web_path):
        if not p.is_file():
            problems.append(f"MISSING SOURCE FILE: {p.relative_to(root) if p.is_relative_to(root) else p}")
    if problems:
        print("\n".join(f"  x {x}" for x in problems))
        print("\nFAIL: expected upstream source files not found — upstream layout changed "
              "or wrong --upstream_dir.")
        return 1

    # Vanilla sanity: refuse to 'pass' against our own fork.
    api_text = api_path.read_text(encoding="utf-8", errors="replace")
    fork_hits = [m for m in FORK_MARKERS if m in api_text]
    if fork_hits:
        print(f"  ✗ FORK MARKERS in {API_SERVER}: {fork_hits}")
        print("\nFAIL: api_server.py contains relay/fork markers — this is NOT vanilla "
              "upstream. The contract must run against unmodified NousResearch/hermes-agent "
              "with no bootstrap .pth loaded.")
        return 1

    found = extract_routes(api_path, _AIOHTTP_RE) | extract_routes(web_path, _FASTAPI_RE)

    missing_required = sorted(REQUIRED - found)
    missing_advisory = sorted(ADVISORY - found)
    present_required = sorted(REQUIRED & found)

    print(f"upstream root : {root}")
    print(f"routes parsed : {len(found)} declared route paths "
          f"({API_SERVER} + {WEB_SERVER})")
    print()
    print("REQUIRED standard-path routes:")
    for r in sorted(REQUIRED):
        print(f"  [{'ok' if r in found else 'MISSING'}] {r}")
    if missing_advisory:
        print("\nADVISORY routes absent (app degrades; not a failure):")
        for r in missing_advisory:
            print(f"  - {r}")

    print()
    if missing_required:
        print(f"FAIL: {len(missing_required)} REQUIRED route(s) missing from vanilla upstream:")
        for r in missing_required:
            print(f"  x {r}")
        print("\nThe standard (no-plugin) path depends on these. Either upstream renamed/"
              "dropped a route (update the client + this contract together) or the pinned "
              "UPSTREAM_REF predates the route.")
        return 1

    print(f"PASS: all {len(present_required)} REQUIRED standard-path routes present on vanilla upstream.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

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
                            ("GET", "/v1/capabilities", handler)
    dashboard  (FastAPI):   @app.post("/api/audio/transcribe")  /  @app.websocket("/api/ws")

Follow mounted FastAPI routers and aiohttp route-table helpers as well as the
original monolithic entry points. Unregistered sibling modules do not establish
the public surface. Source is parsed, never imported or executed.

Parsing them is deterministic, dependency-free, framework-agnostic, and
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

import ast
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
# Current upstream declares the same aiohttp surface as a compact route table
# consumed by `add_routes()`: ("GET", "/path", handler). Keep both shapes so
# this gate validates the public contract instead of one registration style.
_AIOHTTP_TABLE_RE = re.compile(
    r"""\(\s*["'](GET|POST|PATCH|DELETE|PUT|HEAD)["']\s*,\s*["']([^"']+)["']\s*,"""
)
_FASTAPI_METHODS = {"get", "post", "patch", "delete", "put", "head", "websocket"}

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


def imported_names(tree: ast.Module) -> dict[str, str]:
    """Resolve aliases without loading any upstream code or dependencies."""
    names: dict[str, str] = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module and not node.level:
            for alias in node.names:
                names[alias.asname or alias.name] = f"{node.module}.{alias.name}"
        elif isinstance(node, ast.Import):
            for alias in node.names:
                names[alias.asname or alias.name.split(".")[0]] = (
                    alias.name if alias.asname else alias.name.split(".")[0]
                )
    return names


def qualified_name(node: ast.AST, names: dict[str, str], module: str) -> str:
    if isinstance(node, ast.Name):
        return names.get(node.id, f"{module}.{node.id}")
    if isinstance(node, ast.Attribute):
        parent = qualified_name(node.value, names, module)
        return f"{parent}.{node.attr}" if parent else ""
    return ""


def literal_prefix(call: ast.Call) -> str:
    for keyword in call.keywords:
        if keyword.arg == "prefix":
            if isinstance(keyword.value, ast.Constant) and isinstance(keyword.value.value, str):
                return keyword.value.value
            raise ValueError("UNSUPPORTED REGISTRATION: non-literal router prefix")
    return ""


def discover_routes(root: Path) -> tuple[set[str], set[Path]]:
    """Read only entry points and statically registered route source modules."""
    sources: dict[str, tuple[str, ast.Module]] = {}

    def read(module: str) -> tuple[str, ast.Module]:
        if module not in sources:
            path = root / (module.replace(".", "/") + ".py")
            if not path.is_file():
                raise ValueError(f"MISSING SOURCE FILE: {path.relative_to(root)}")
            text = path.read_text(encoding="utf-8", errors="replace")
            if module.startswith("gateway.platforms.api_server"):
                hits = [marker for marker in FORK_MARKERS if marker in text]
                if hits:
                    raise ValueError(f"FORK MARKERS in {path.relative_to(root)}: {hits}")
            sources[module] = (text, ast.parse(text, filename=str(path)))
        return sources[module]

    api_seen: set[tuple[str, str]] = set()

    def api_routes(module: str, function: str = "") -> set[str]:
        key = (module, function)
        if key in api_seen:
            return set()
        api_seen.add(key)
        text, tree = read(module)
        scope: ast.AST = tree
        if function:
            definitions = [node for node in tree.body
                           if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                           and node.name == function]
            if not definitions:
                raise ValueError(f"MISSING ROUTE HELPER: {module}.{function}")
            scope = definitions[0]
            text = ast.get_source_segment(text, scope) or ""
        found = {match.group(2) for pattern in (_AIOHTTP_RE, _AIOHTTP_TABLE_RE)
                 for match in pattern.finditer(text)}
        names = imported_names(tree)
        # Modular tables are consumed via routes.extend(helper(self)).
        # An import or a standalone helper call is not registration evidence.
        for node in ast.walk(scope):
            if (isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
                    and node.func.attr == "extend" and node.args
                    and isinstance(node.args[0], ast.Call)):
                target = qualified_name(node.args[0].func, names, module)
                owner, _, member = target.rpartition(".")
                if owner.startswith("gateway.platforms.api_server_"):
                    found |= api_routes(owner, member)
        return found

    router_stack: set[tuple[str, str]] = set()

    def web_routes(module: str, router: str, prefix: str = "") -> set[str]:
        key = (module, router)
        if key in router_stack:
            raise ValueError(f"CYCLIC ROUTER REGISTRATION: {module}.{router}")
        router_stack.add(key)
        _, tree = read(module)
        names = imported_names(tree)
        target = f"{module}.{router}"
        if router != "app":
            declarations = [node.value for node in tree.body
                            if isinstance(node, ast.Assign)
                            and any(isinstance(item, ast.Name) and item.id == router
                                    for item in node.targets)
                            and isinstance(node.value, ast.Call)]
            if not declarations:
                raise ValueError(f"MISSING ROUTER: {target}")
            prefix += literal_prefix(declarations[0])
        found: set[str] = set()
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
                continue
            if qualified_name(node.func.value, names, module) != target or not node.args:
                continue
            if node.func.attr in _FASTAPI_METHODS:
                path = node.args[0]
                if isinstance(path, ast.Constant) and isinstance(path.value, str):
                    found.add(prefix + path.value)
            elif node.func.attr == "include_router":
                child = qualified_name(node.args[0], names, module)
                owner, _, member = child.rpartition(".")
                # Dynamic plugin mounts are outside the vanilla surface. A
                # local router must have a static declaration in this module.
                local_router = owner == module and any(
                    isinstance(item, ast.Assign) and isinstance(item.value, ast.Call)
                    and qualified_name(item.value.func, names, module).endswith(".APIRouter")
                    and any(isinstance(name, ast.Name) and name.id == member
                            for name in item.targets)
                    for item in tree.body
                )
                if local_router or owner.startswith("hermes_cli.web_routers."):
                    found |= web_routes(owner, member, prefix + literal_prefix(node))
        router_stack.remove(key)
        return found

    found = api_routes(API_SERVER.removesuffix(".py").replace("/", "."))
    found |= web_routes(WEB_SERVER.removesuffix(".py").replace("/", "."), "app")
    paths = {Path(module.replace(".", "/") + ".py") for module in sources}
    return found, paths


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("UPSTREAM_DIR", ".")).resolve()
    try:
        found, sources = discover_routes(root)
    except (OSError, SyntaxError, ValueError) as error:
        print(f"  x {error}")
        print("\nFAIL: expected vanilla upstream source/registration not found. "
              "Check the source layout and upstream root; relay/fork sources must not be used.")
        return 1

    missing_required = sorted(REQUIRED - found)
    missing_advisory = sorted(ADVISORY - found)
    present_required = sorted(REQUIRED & found)

    print(f"upstream root : {root}")
    print(f"routes parsed : {len(found)} declared route paths ({len(sources)} registered source files)")
    for source in sorted(sources):
        print(f"  source: {source.as_posix()}")
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

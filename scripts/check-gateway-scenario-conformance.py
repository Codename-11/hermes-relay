#!/usr/bin/env python3
"""Provider-free source conformance for reusable Gateway scenarios.

The check is intentionally on-demand and non-mutating. It inspects a clean
vanilla ``NousResearch/hermes-agent`` checkout without starting the Gateway,
opening a database, creating a session, or resolving provider credentials.

An optional JSON scenario manifest may select a subset of contracts with a
top-level ``contract_requirements`` (or ``requires``) string array. Without a
manifest, all known contracts are checked.
"""

from __future__ import annotations

import argparse
import ast
import json
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable, Sequence


SERVER = "tui_gateway/server.py"
SESSION_METHODS = "tui_gateway/methods_session.py"
API_SERVER = "gateway/platforms/api_server.py"

GATEWAY_TERMINAL = "gateway.message_complete"
GATEWAY_SETTLED_INFO = "gateway.settled_session_info"
SESSION_ACTIVATE = "gateway.session_activate_live"
SESSION_RESUME = "gateway.session_resume_durable"
API_BOUNDARY = "api.fallback_boundary"
ALL_CONTRACTS = (
    GATEWAY_TERMINAL,
    GATEWAY_SETTLED_INFO,
    SESSION_ACTIVATE,
    SESSION_RESUME,
    API_BOUNDARY,
)

FORK_MARKERS = ("hermes_relay", "hermes-relay", "RelayPlugin")
VANILLA_REMOTE_MARKER = "nousresearch/hermes-agent"


@dataclass(frozen=True)
class CheckResult:
    contract: str
    passed: bool
    evidence: tuple[str, ...]
    problem: str | None = None


class SourceFile:
    def __init__(self, root: Path, relative: str):
        self.relative = relative
        self.path = root / relative
        if not self.path.is_file():
            raise ValueError(f"missing upstream source file: {relative}")
        self.text = self.path.read_text(encoding="utf-8", errors="replace")
        try:
            self.tree = ast.parse(self.text, filename=relative)
        except SyntaxError as exc:
            raise ValueError(f"could not parse upstream source file {relative}: {exc}") from exc

    def function(self, name: str) -> ast.FunctionDef | ast.AsyncFunctionDef:
        for node in ast.walk(self.tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name:
                return node
        raise ValueError(f"missing function {name} in {self.relative}")

    def method_handler(self, method_name: str) -> ast.FunctionDef | ast.AsyncFunctionDef:
        for node in ast.walk(self.tree):
            if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                continue
            for decorator in node.decorator_list:
                if (
                    isinstance(decorator, ast.Call)
                    and isinstance(decorator.func, ast.Name)
                    and decorator.func.id == "method"
                    and decorator.args
                    and isinstance(decorator.args[0], ast.Constant)
                    and decorator.args[0].value == method_name
                ):
                    return node
        raise ValueError(f"missing @method({method_name!r}) handler in {self.relative}")

    def segment(self, node: ast.AST) -> str:
        return ast.get_source_segment(self.text, node) or ""

    def evidence(self, node: ast.AST, label: str) -> str:
        return f"{self.relative}:{getattr(node, 'lineno', 1)}:{label}"


def _string_constants(node: ast.AST) -> set[str]:
    return {
        child.value
        for child in ast.walk(node)
        if isinstance(child, ast.Constant) and isinstance(child.value, str)
    }


def _call_lines(node: ast.AST, name: str, first_string: str | None = None) -> list[int]:
    lines: list[int] = []
    for child in ast.walk(node):
        if not isinstance(child, ast.Call):
            continue
        called = ""
        if isinstance(child.func, ast.Name):
            called = child.func.id
        elif isinstance(child.func, ast.Attribute):
            called = child.func.attr
        if called != name:
            continue
        if first_string is not None:
            if not child.args or not isinstance(child.args[0], ast.Constant):
                continue
            if child.args[0].value != first_string:
                continue
        lines.append(child.lineno)
    return sorted(lines)


def _running_false_lines(node: ast.AST) -> list[int]:
    lines: list[int] = []
    for child in ast.walk(node):
        if not isinstance(child, (ast.Assign, ast.AnnAssign)):
            continue
        targets = child.targets if isinstance(child, ast.Assign) else [child.target]
        value = child.value
        if not isinstance(value, ast.Constant) or value.value is not False:
            continue
        for target in targets:
            if (
                isinstance(target, ast.Subscript)
                and isinstance(target.value, ast.Name)
                and target.value.id == "session"
                and isinstance(target.slice, ast.Constant)
                and target.slice.value == "running"
            ):
                lines.append(child.lineno)
    return sorted(lines)


def _settled_finally_pair(node: ast.AST) -> tuple[int, int] | None:
    """Find running=False followed by settled-info in the same finally suite."""
    pairs: list[tuple[int, int]] = []
    for child in ast.walk(node):
        if not isinstance(child, ast.Try) or not child.finalbody:
            continue
        suite = ast.Module(body=child.finalbody, type_ignores=[])
        false_lines = _running_false_lines(suite)
        settle_lines = _call_lines(suite, "_emit_settled_session_info")
        pairs.extend(
            (running_line, settle_line)
            for settle_line in settle_lines
            for running_line in false_lines
            if running_line < settle_line
        )
    return max(pairs, key=lambda pair: pair[0]) if pairs else None


def _check_gateway_terminal(server: SourceFile) -> CheckResult:
    contract = GATEWAY_TERMINAL
    try:
        turn = server.function("_run_prompt_submit")
        complete = _call_lines(turn, "_emit", "message.complete")
        if not complete:
            raise ValueError("_run_prompt_submit no longer emits message.complete")
        return CheckResult(
            contract,
            True,
            (server.evidence(turn, f"message.complete emit at line {complete[0]}"),),
        )
    except ValueError as exc:
        return CheckResult(contract, False, (), str(exc))


def _check_settled_info(server: SourceFile) -> CheckResult:
    contract = GATEWAY_SETTLED_INFO
    try:
        info = server.function("_session_info")
        helper = server.function("_emit_settled_session_info")
        turn = server.function("_run_prompt_submit")
        info_strings = _string_constants(info)
        helper_text = server.segment(helper)
        if "running" not in info_strings or ".get(\"running\")" not in server.segment(info):
            raise ValueError("session.info no longer derives running from live session state")
        if '"session.info"' not in helper_text or "_session_info(" not in helper_text:
            raise ValueError(
                "settled session.info helper no longer emits authoritative session state"
            )
        settle_pair = _settled_finally_pair(turn)
        if settle_pair is None:
            raise ValueError("turn finalizer no longer clears running before settled session.info")
        running_line, settle_line = settle_pair
        return CheckResult(
            contract,
            True,
            (
                server.evidence(info, "session.info includes running"),
                server.evidence(helper, "settled session.info emission"),
                f"{SERVER}:{running_line}:running=false before line {settle_line}",
            ),
        )
    except ValueError as exc:
        return CheckResult(contract, False, (), str(exc))


def _check_activate(server: SourceFile, methods: SourceFile) -> CheckResult:
    contract = SESSION_ACTIVATE
    try:
        handler = methods.method_handler("session.activate")
        payload = server.function("_live_session_payload")
        handler_text = methods.segment(handler)
        payload_strings = _string_constants(payload)
        required_calls = ("_sess_nowait(", "_live_session_payload(")
        missing_calls = [marker for marker in required_calls if marker not in handler_text]
        required_fields = {"session_id", "session_key", "messages", "running", "status"}
        missing_fields = sorted(required_fields - payload_strings)
        if missing_calls:
            raise ValueError(
                "session.activate missing live-only seam(s): " + ", ".join(missing_calls)
            )
        if "_inflight_snapshot(" not in server.segment(payload):
            raise ValueError("live activation payload no longer snapshots the in-flight turn")
        if missing_fields:
            raise ValueError(
                "live activation payload missing field(s): " + ", ".join(missing_fields)
            )
        return CheckResult(
            contract,
            True,
            (
                methods.evidence(handler, "session.activate resolves an existing live id"),
                server.evidence(
                    payload, "live payload carries identity, history, inflight, and running"
                ),
            ),
        )
    except ValueError as exc:
        return CheckResult(contract, False, (), str(exc))


def _check_resume(methods: SourceFile) -> CheckResult:
    contract = SESSION_RESUME
    try:
        handler = methods.method_handler("session.resume")
        text = methods.segment(handler)
        strings = _string_constants(handler)
        required_markers = (
            "db.get_session(",
            "db.resolve_resume_session_id(",
            "_find_live_session_by_key(",
            "_live_session_payload(",
        )
        missing_markers = [marker for marker in required_markers if marker not in text]
        required_fields = {"session_id", "session_key", "messages", "running", "status", "resumed"}
        missing_fields = sorted(required_fields - strings)
        if missing_markers:
            raise ValueError(
                "session.resume missing durable seam(s): " + ", ".join(missing_markers)
            )
        if missing_fields:
            raise ValueError(
                "session.resume missing response/error field(s): " + ", ".join(missing_fields)
            )
        if not {4007, 4130}.issubset(
            {
                child.value
                for child in ast.walk(handler)
                if isinstance(child, ast.Constant) and isinstance(child.value, int)
            }
        ):
            raise ValueError("session.resume no longer exposes not-found and over-limit rejection")
        return CheckResult(
            contract,
            True,
            (
                methods.evidence(
                    handler,
                    "durable lookup, lineage resolution, live reuse, and explicit rejection",
                ),
            ),
        )
    except ValueError as exc:
        return CheckResult(contract, False, (), str(exc))


def _check_api_boundary(api: SourceFile) -> CheckResult:
    contract = API_BOUNDARY
    try:
        session_stream = api.function("_handle_session_chat_stream")
        chat_completions = api.function("_handle_chat_completions")
        runs = api.function("_handle_runs")
        source_strings = _string_constants(api.tree)
        required_routes = {
            "/api/sessions/{session_id}/chat/stream",
            "/v1/chat/completions",
            "/v1/runs",
            "/v1/runs/{run_id}/events",
        }
        missing_routes = sorted(required_routes - source_strings)
        stream_events = _string_constants(session_stream)
        run_events = _string_constants(runs)
        missing_stream = sorted({"assistant.completed", "run.completed", "done"} - stream_events)
        missing_runs = sorted({"message.delta", "run.completed"} - run_events)
        if missing_routes:
            raise ValueError("API fallback route(s) missing: " + ", ".join(missing_routes))
        if missing_stream:
            raise ValueError("session SSE terminal event(s) missing: " + ", ".join(missing_stream))
        if missing_runs:
            raise ValueError("run SSE event(s) missing: " + ", ".join(missing_runs))
        return CheckResult(
            contract,
            True,
            (
                api.evidence(
                    session_stream, "session SSE owns assistant.completed/run.completed/done"
                ),
                api.evidence(chat_completions, "OpenAI-compatible fallback is a separate handler"),
                api.evidence(runs, "run API owns message.delta/run.completed lifecycle"),
            ),
        )
    except ValueError as exc:
        return CheckResult(contract, False, (), str(exc))


def load_requirements(manifest: Path | None) -> tuple[str, ...]:
    if manifest is None:
        return ALL_CONTRACTS
    try:
        payload = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"could not read scenario manifest: {exc}") from exc
    if not isinstance(payload, dict):
        raise ValueError("scenario manifest must be a JSON object")
    raw = payload.get("contract_requirements", payload.get("requires"))
    if raw is None:
        return ALL_CONTRACTS
    if not isinstance(raw, list) or not raw or not all(isinstance(item, str) for item in raw):
        raise ValueError("scenario manifest contract_requirements must be a non-empty string array")
    unknown = sorted(set(raw) - set(ALL_CONTRACTS))
    if unknown:
        raise ValueError("unknown scenario contract requirement(s): " + ", ".join(unknown))
    requested = set(raw)
    return tuple(contract for contract in ALL_CONTRACTS if contract in requested)


def audit_sources(root: Path, requirements: Iterable[str]) -> list[CheckResult]:
    server = SourceFile(root, SERVER)
    methods = SourceFile(root, SESSION_METHODS)
    api = SourceFile(root, API_SERVER)
    source_files = (server, methods, api)
    fork_hits = [
        f"{source.relative}:{marker}"
        for source in source_files
        for marker in FORK_MARKERS
        if marker in source.text
    ]
    if fork_hits:
        raise ValueError("fork marker(s) found in upstream source: " + ", ".join(fork_hits))

    checks = {
        GATEWAY_TERMINAL: lambda: _check_gateway_terminal(server),
        GATEWAY_SETTLED_INFO: lambda: _check_settled_info(server),
        SESSION_ACTIVATE: lambda: _check_activate(server, methods),
        SESSION_RESUME: lambda: _check_resume(methods),
        API_BOUNDARY: lambda: _check_api_boundary(api),
    }
    return [checks[requirement]() for requirement in requirements]


def _git(root: Path, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ("git", *args),
        cwd=root,
        check=check,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def inspect_checkout(root: Path) -> str:
    if not (root / ".git").exists():
        raise ValueError(f"not a Git checkout: {root}")
    sha = _git(root, "rev-parse", "HEAD").stdout.strip()
    if _git(root, "status", "--porcelain").stdout.strip():
        raise ValueError("upstream checkout is dirty; inspect an exact clean revision")
    remotes = _git(root, "remote", "-v").stdout.lower().replace("\\", "/")
    if VANILLA_REMOTE_MARKER not in remotes:
        raise ValueError("no NousResearch/hermes-agent remote found; refusing non-vanilla evidence")
    return sha


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("upstream", type=Path, help="clean vanilla hermes-agent Git checkout")
    parser.add_argument("--scenario-manifest", type=Path, help="optional JSON scenario manifest")
    parser.add_argument(
        "--evidence-json", type=Path, help="write sanitized machine-readable evidence"
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    root = args.upstream.expanduser().resolve()
    try:
        sha = inspect_checkout(root)
        requirements = load_requirements(
            args.scenario_manifest.expanduser().resolve() if args.scenario_manifest else None
        )
        results = audit_sources(root, requirements)
    except ValueError as exc:
        print(f"FAIL: {exc}")
        return 1

    evidence = {
        "schema": 1,
        "scope": "provider_free_gateway_scenario_conformance",
        "upstream_sha": sha,
        "upstream_clean": True,
        "scenario_manifest": args.scenario_manifest.name if args.scenario_manifest else None,
        "checks": [asdict(result) for result in results],
        "provider_calls": "not_run",
        "gateway_runtime": "not_started",
    }
    rendered = json.dumps(evidence, indent=2, sort_keys=True) + "\n"
    if args.evidence_json:
        args.evidence_json.expanduser().resolve().write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0 if all(result.passed for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())

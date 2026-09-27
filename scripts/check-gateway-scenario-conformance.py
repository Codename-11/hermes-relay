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
PROMPT_METHODS = "tui_gateway/methods_prompt.py"
ACTIVE_SESSIONS = "hermes_cli/active_sessions.py"
API_SERVER = "gateway/platforms/api_server.py"

GATEWAY_TERMINAL = "gateway.message_complete"
GATEWAY_SETTLED_INFO = "gateway.settled_session_info"
SESSION_ACTIVATE = "gateway.session_activate_live"
SESSION_RESUME = "gateway.session_resume_durable"
SESSION_ACTIVE_LIST = "gateway.session_active_list"
SESSION_EXCLUSIVE_SUBMIT = "gateway.session_exclusive_submit"
SUBAGENT_CHILD_WATCH = "gateway.subagent_child_watch"
SESSION_INITIALIZATION = "gateway.session_initialization"
API_BOUNDARY = "api.fallback_boundary"
CLARIFY = "gateway.clarify"
SERVER_REQUESTS = "gateway.server_requests"
TICKET_PROTOCOL = "gateway.ticket_subprotocol"
ALL_CONTRACTS = (
    TICKET_PROTOCOL,
    SERVER_REQUESTS,
    GATEWAY_TERMINAL,
    GATEWAY_SETTLED_INFO,
    SESSION_ACTIVATE,
    SESSION_RESUME,
    SESSION_ACTIVE_LIST,
    SESSION_EXCLUSIVE_SUBMIT,
    SUBAGENT_CHILD_WATCH,
    SESSION_INITIALIZATION,
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
        self.root = root
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
                    and decorator.func.id in {"method", "_session_method"}
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


def _check_session_initialization(server: SourceFile) -> CheckResult:
    try:
        build = server.function("_start_agent_build")
        if not _call_lines(build, "_emit", "error") or "agent init failed:" not in server.segment(build):
            raise ValueError("deferred build no longer emits the initialization failure")
        ready_owner = build
        if _call_lines(build, "_announce_built_agent"):
            ready_owner = server.function("_announce_built_agent")
        if not _call_lines(ready_owner, "_emit", "session.info"):
            raise ValueError("deferred build no longer emits the ready session.info")
        return CheckResult(SESSION_INITIALIZATION, True, (server.evidence(build, "ready and initialization-failure events"),))
    except ValueError as exc:
        return CheckResult(SESSION_INITIALIZATION, False, (), str(exc))


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
        required_calls = ("_live_session_payload(",)
        missing_calls = [marker for marker in required_calls if marker not in handler_text]
        if "_sess_nowait(" not in handler_text:
            # Upstream moved live-id lookup into its session decorator. Verify
            # that wrapper rather than accepting any similarly named alias.
            wrapped = any(
                isinstance(decorator, ast.Call)
                and isinstance(decorator.func, ast.Name)
                and decorator.func.id == "_session_method"
                and not decorator.keywords
                for decorator in handler.decorator_list
            )
            wrapper = methods.function("_session_method") if wrapped else None
            bindings = [node for node in methods.tree.body if isinstance(node, ast.Assign)
                        and any(isinstance(t, ast.Name) and t.id == "_with_session" for t in node.targets)]
            if not (wrapper and "method(name)" in methods.segment(wrapper)
                    and "_with_live_session if live else _with_session" in methods.segment(wrapper)
                    and "live: bool = False" in methods.segment(wrapper)
                    and any(_call_lines(node, "_sess_nowait") for node in bindings)):
                missing_calls.append("_sess_nowait(")
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


def _check_active_list(server: SourceFile, methods: SourceFile) -> CheckResult:
    contract = SESSION_ACTIVE_LIST
    try:
        status = server.function("_session_live_status")
        item = server.function("_session_live_item")
        handler = methods.method_handler("session.active_list")
        status_text = server.segment(status)
        item_strings = _string_constants(item)
        handler_text = methods.segment(handler)
        missing_statuses = sorted(
            {"starting", "working", "waiting", "idle"} - _string_constants(status)
        )
        if missing_statuses:
            raise ValueError("live status missing state(s): " + ", ".join(missing_statuses))
        pending_at = status_text.find("_session_pending_kind(")
        running_at = status_text.find('.get("running")')
        if pending_at < 0 or running_at < 0 or pending_at > running_at:
            raise ValueError("waiting state no longer takes precedence over running")
        missing_fields = sorted({"id", "session_key", "status"} - item_strings)
        if missing_fields:
            raise ValueError("active-list row missing field(s): " + ", ".join(missing_fields))
        snapshot_node = handler
        snapshot_text = handler_text
        if "_snapshot_sessions(" in handler_text:
            snapshot_node = methods.function("_snapshot_sessions")
            snapshot_text = methods.segment(snapshot_node)
        required_snapshot_markers = ("_sessions_lock", "_sessions.items()")
        missing_snapshot_markers = [
            marker for marker in required_snapshot_markers if marker not in snapshot_text
        ]
        missing_markers = list(missing_snapshot_markers)
        if "_session_live_item(" not in handler_text:
            missing_markers.append("_session_live_item(")
        if missing_markers or "sessions" not in _string_constants(handler):
            raise ValueError(
                "session.active_list no longer snapshots the live registry: "
                + ", ".join(missing_markers or ["sessions result"])
            )
        handler_strings = _string_constants(handler)
        if "current_session_id" not in handler_strings:
            raise ValueError("session.active_list no longer accepts current_session_id")
        if "profile" in handler_strings:
            raise ValueError("session.active_list unexpectedly claims a profile filter")
        return CheckResult(
            contract,
            True,
            (
                server.evidence(status, "starting, working, waiting, and idle derivation"),
                server.evidence(item, "live row carries runtime and durable identities"),
                methods.evidence(
                    snapshot_node, "active list snapshots the process-wide in-memory registry"
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


def _check_subagent_child_watch(server: SourceFile, methods: SourceFile) -> CheckResult:
    contract = SUBAGENT_CHILD_WATCH
    try:
        resume = methods.method_handler("session.resume")
        resume_segment = methods.segment(resume)
        if "_resume_lazy(ctx)" in resume_segment:
            lazy = methods.function("_resume_lazy")
            child_history = methods.function("child_history")
            constructor = next((
                node for node in methods.tree.body
                if isinstance(node, ast.ClassDef) and node.name == "_Resume"
            ), None)
            if constructor is None:
                raise ValueError("missing _Resume context")
            lazy_text = methods.segment(lazy)
            history_text = methods.segment(child_history)
            if not {"lazy", "close_on_disconnect"} <= _string_constants(constructor):
                raise ValueError("resume context no longer carries lazy/close_on_disconnect")
            if "ctx.child_history(" not in lazy_text or "lazy=True" not in lazy_text:
                raise ValueError("lazy resume no longer creates a child-history watcher")
            if "get_messages_as_conversation(self.target" not in history_text or "include_ancestors=True" in history_text:
                raise ValueError("child history no longer uses the child-only conversation")
            mirror = SourceFile(server.root, "tui_gateway/agent_callbacks.py")
            mirror_fn = mirror.function("_mirror_subagent_to_child")
            if not {"child_session_id", "subagent.text", "reasoning.delta", "message.delta"} <= _string_constants(mirror.tree):
                raise ValueError("child mirror event contract missing")
            return CheckResult(contract, True, (
                methods.evidence(resume, "session.resume dispatches lazy watch"),
                methods.evidence(lazy, "lazy child-only history and live status"),
                mirror.evidence(mirror_fn, "child_session_id routes child mirror events"),
            ))
        resume_strings = _string_constants(resume)
        server_strings = _string_constants(server.tree)
        missing_resume = sorted(
            {"lazy", "close_on_disconnect"} - resume_strings
        )
        if missing_resume:
            raise ValueError("lazy child resume field(s) missing: " + ", ".join(missing_resume))
        if "include_ancestors" not in resume_segment:
            raise ValueError("lazy child resume does not declare child-only history")
        missing_events = sorted(
            {"child_session_id", "subagent.text", "reasoning.delta", "message.delta"}
            - server_strings
        )
        if missing_events:
            raise ValueError("child watch mirror event(s) missing: " + ", ".join(missing_events))
        return CheckResult(
            contract,
            True,
            (
                methods.evidence(resume, "session.resume supports a lazy child-only watch"),
                "tui_gateway/server.py: child_session_id routes child mirror events",
            ),
        )
    except ValueError as exc:
        return CheckResult(contract, False, (), str(exc))


def _check_session_exclusive_submit(
    server: SourceFile,
    session_methods: SourceFile,
    prompt_methods: SourceFile,
    active_sessions: SourceFile,
) -> CheckResult:
    contract = SESSION_EXCLUSIVE_SUBMIT
    try:
        submit = prompt_methods.method_handler("prompt.submit")
        create = session_methods.method_handler("session.create")
        build = server.function("_start_agent_build")
        run_submit = server.function("_run_prompt_submit")
        submit_text = prompt_methods.segment(submit)
        create_text = session_methods.segment(create)
        build_text = server.segment(build)
        run_text = server.segment(run_submit)
        active_text = active_sessions.text
        missing: list[str] = []
        if "_ensure_active_session_slot" not in submit_text or "4090" not in submit_text:
            missing.append("prompt.submit atomic ownership refusal")
        if '"reason"' not in submit_text:
            missing.append("machine-readable refusal reason")
        if "_ensure_active_session_slot" not in run_text or '"error"' not in run_text:
            missing.append("defense-in-depth terminal error event")
        if "_schedule_agent_build" not in create_text or '"lazy"' not in create_text:
            missing.append("lazy session.create readiness contract")
        if '"session.info"' not in build_text or "ready.set()" not in build_text:
            missing.append("deferred agent-ready session.info edge")
        for marker in (
            'SESSION_NOT_OWNED = "SESSION_NOT_OWNED"',
            "PER_SESSION_EXCLUSIVE_SUBMIT = True",
            "already has a live owner",
            "Only one surface at a time may run a session",
        ):
            if marker not in active_text:
                missing.append(marker)
        if missing:
            raise ValueError("exclusive submit contract missing: " + ", ".join(missing))
        return CheckResult(
            contract,
            True,
            (
                prompt_methods.evidence(submit, "prompt.submit refuses ownership conflicts before turn start"),
                session_methods.evidence(create, "session.create advertises a lazy deferred build"),
                server.evidence(build, "deferred build emits session.info before setting ready"),
                server.evidence(run_submit, "synthesized turns recheck ownership and emit terminal error"),
                f"{ACTIVE_SESSIONS}: SESSION_NOT_OWNED and canonical holder-aware message",
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


def _check_server_requests(root: Path) -> CheckResult:
    requests = SourceFile(root, "tui_gateway/server_requests.py")
    contracts = SourceFile(root, "tui_gateway/contracts/server_requests.py")
    prompt = SourceFile(root, PROMPT_METHODS)
    voice = SourceFile(root, "tui_gateway/methods_voice.py")
    bridge = SourceFile(root, SERVER)
    frame = requests.function("frame")
    snapshot = requests.function("snapshot")
    resolve = requests.function("resolve_response")
    lock = requests.function("lock_answer")
    send = requests.function("send")
    cancel = requests.function("_emit_cancel")
    lock_rpc = prompt.method_handler("clarify.lock")
    capabilities = voice.method_handler("client.capabilities")
    expected = {"clarify", "approval", "sudo", "secret", "vault.unlock_prompt", "vault.save_login",
                "vault.code", "terminal.read", "preview.read", "preview.act", "window.read", "tour"}
    declared = {node.args[0].value for node in ast.walk(contracts.tree)
                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                and node.func.id == "server_request" and node.args and isinstance(node.args[0], ast.Constant)}
    passed = (
        expected == declared
        and {"jsonrpc", "id", "method", "params", "session_id"} <= _string_constants(frame)
        and "answers" in _string_constants(snapshot)
        and {"error", "result", "answers"} <= _string_constants(resolve)
        and {"answers", "timed_out", "timeout"} <= _string_constants(send)
        and bool(_call_lines(send, "_unanswerable"))
        and {"request.cancel", "id", "method", "reason"} <= _string_constants(cancel)
        and {"request_id", "question_id", "answer", "expired", "remaining"} <= _string_constants(lock_rpc)
        and bool(_call_lines(lock_rpc, "lock_answer"))
        and bool(_call_lines(capabilities, "advertise"))
        and bool(_call_lines(bridge.function("_clarify_block"), "send", "clarify"))
        and "req.locked[question_id] = answer" in requests.segment(lock)
    )
    return CheckResult(SERVER_REQUESTS, passed, (
        requests.evidence(frame, "typed request envelope"),
        requests.evidence(snapshot, "open request replay with accepted locks"),
        requests.evidence(resolve, "result or error settlement"),
        requests.evidence(send, "capability gate and partial timeout"),
        requests.evidence(cancel, "scoped cancellation"),
        prompt.evidence(lock_rpc, "per-question lock RPC"),
        voice.evidence(capabilities, "per-transport capabilities"),
    ), None if passed else "Server request methods, envelope, capability, replay or settlement contract changed")


def _check_clarify(server: SourceFile) -> CheckResult:
    bridge = server.function("_clarify_block")
    respond = server.function("_respond")
    replay = server.function("_pending_clarify_request_payload")
    block = server.function("_block")
    required = (
        {"questions", "qid", "question", "choices", "multi_select"} <= _string_constants(bridge)
        and {"question_id", "request_id", "answers", "remaining", "expired"} <= _string_constants(respond)
        and {"answers", "clarify.request"} <= _string_constants(replay)
        and {"answers", "timed_out"} <= _string_constants(block)
    )
    return CheckResult(
        CLARIFY, required,
        tuple(server.evidence(node, label) for node, label in (
            (bridge, "legacy and qid batch wire"), (respond, "per-question response"),
            (replay, "answered-qid replay"), (block, "partial timeout"),
        )),
        None if required else "Clarify wire, response, replay, or partial-timeout contract changed",
    )


def audit_sources(root: Path, requirements: Iterable[str]) -> list[CheckResult]:
    server = SourceFile(root, SERVER)
    methods = SourceFile(root, SESSION_METHODS)
    prompt_methods = SourceFile(root, PROMPT_METHODS)
    active_sessions = SourceFile(root, ACTIVE_SESSIONS)
    api = SourceFile(root, API_SERVER)
    source_files = (server, methods, prompt_methods, active_sessions, api)
    fork_hits = [
        f"{source.relative}:{marker}"
        for source in source_files
        for marker in FORK_MARKERS
        if marker in source.text
    ]
    if fork_hits:
        raise ValueError("fork marker(s) found in upstream source: " + ", ".join(fork_hits))

    checks = {
        TICKET_PROTOCOL: lambda: _check_ticket_protocol(
            SourceFile(root, "hermes_cli/web_server_chat.py"),
            SourceFile(root, "hermes_cli/web_routers/chat_ws.py"),
        ),
        CLARIFY: lambda: _check_clarify(server),
        SERVER_REQUESTS: lambda: _check_server_requests(root),
        GATEWAY_TERMINAL: lambda: _check_gateway_terminal(
            SourceFile(root, "tui_gateway/prompt_turn.py")
            if (root / "tui_gateway/prompt_turn.py").is_file() else server
        ),
        GATEWAY_SETTLED_INFO: lambda: _check_settled_info(server),
        SESSION_ACTIVATE: lambda: _check_activate(server, methods),
        SESSION_RESUME: lambda: _check_resume(methods),
        SESSION_ACTIVE_LIST: lambda: _check_active_list(server, methods),
        SESSION_EXCLUSIVE_SUBMIT: lambda: _check_session_exclusive_submit(
            server, methods, prompt_methods, active_sessions,
        ),
        SUBAGENT_CHILD_WATCH: lambda: _check_subagent_child_watch(server, methods),
        SESSION_INITIALIZATION: lambda: _check_session_initialization(server),
        API_BOUNDARY: lambda: _check_api_boundary(api),
    }
    return [checks[requirement]() for requirement in requirements]


def _check_ticket_protocol(auth: SourceFile, router: SourceFile) -> CheckResult:
    parser = auth.function("_gateway_ws_ticket_from_subprotocol")
    admission = auth.function("_ws_auth_reason")
    upgrade = router.function("gateway_ws")
    passed = (
        {"hermes-gateway-v1", "hermes-gateway-ticket."} <= _string_constants(auth.tree)
        and {"sec-websocket-protocol", "invalid", "ok"} <= _string_constants(parser)
        and bool(_call_lines(admission, "_gateway_ws_ticket_from_subprotocol"))
        and bool(_call_lines(admission, "consume_ticket"))
        and {"ticket", "ticket-subprotocol"} <= _string_constants(admission)
        and "ws._hermes_ws_subprotocol = _GATEWAY_WS_PROTOCOL" in auth.segment(admission)
        and any(
            isinstance(node, ast.Call)
            and isinstance(node.func, ast.Name) and node.func.id == "handle_ws"
            and any(keyword.arg == "subprotocol" and "_hermes_ws_subprotocol" in
                    _string_constants(keyword.value) for keyword in node.keywords)
            for node in ast.walk(upgrade)
        )
    )
    return CheckResult(
        TICKET_PROTOCOL, passed,
        (auth.evidence(parser, "ticket protocol parser"),
         auth.evidence(admission, "single-use query or protocol admission"),
         router.evidence(upgrade, "public protocol selection")),
        None if passed else "Gateway ticket admission or public subprotocol selection changed",
    )


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

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "check-gateway-scenario-conformance.py"
SPEC = importlib.util.spec_from_file_location("gateway_scenario_conformance", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


SERVER_SOURCE = '''
def _session_info(agent, session=None):
    return {"running": bool((session or {}).get("running"))}

def _emit_settled_session_info(sid, session, agent):
    _emit("session.info", sid, _session_info(agent, session))

def _live_session_payload(sid, session):
    inflight = _inflight_snapshot(session)
    return {
        "session_id": sid, "session_key": "key", "messages": [],
        "running": False, "status": "idle",
    }

def _start_agent_build(sid, session):
    _emit("session.info", sid, {"lazy": False})
    ready.set()

def _run_prompt_submit(sid, session, agent):
    if _ensure_active_session_slot(sid, session) is not None:
        _emit("error", sid, {})
        return False
    try:
        _emit("message.complete", sid, {})
    finally:
        session["running"] = False
        _emit_settled_session_info(sid, session, agent)

def _session_pending_kind(sid):
    return "approval" if sid in _pending else ""

def _session_live_status(sid, session):
    if _session_pending_kind(sid):
        return "waiting"
    ready = session.get("agent_ready")
    if ready is not None and not ready.is_set() and session.get("agent_build_started"):
        return "starting"
    if session.get("running"):
        return "working"
    return "idle"

def _session_live_item(sid, session, current_sid=""):
    return {
        "id": sid,
        "session_key": session.get("session_key", sid),
        "status": _session_live_status(sid, session),
    }

def _mirror_subagent_child(event):
    child = event.get("child_session_id")
    if event.get("type") == "subagent.text":
        return (child, "reasoning.delta", "message.delta")
    return child
'''

METHODS_SOURCE = '''
def method(name):
    return lambda fn: fn

@method("session.create")
def _(rid, params):
    _schedule_agent_build("live")
    return {"session_id": "live", "info": {"lazy": True}}

@method("session.resume")
def _(rid, params):
    target = params.get("session_id", "")
    lazy = bool(params.get("lazy"))
    close_on_disconnect = bool(params.get("close_on_disconnect"))
    include_ancestors = not lazy
    found = db.get_session(target)
    if not found:
        return _err(rid, 4007, "session not found")
    try:
        db.assert_resume_safe(target)
    except TooLarge:
        return _err(rid, 4130, "too large")
    target = db.resolve_resume_session_id(target)
    live = _find_live_session_by_key(target)
    if live:
        payload = _live_session_payload(*live)
        payload["resumed"] = target
        return payload
    return {
        "session_id": "live", "session_key": target, "messages": [],
        "running": False, "status": "idle", "resumed": target,
    }

@method("session.activate")
def _(rid, params):
    session, error = _sess_nowait(params, rid)
    return _live_session_payload(params["session_id"], session)

@method("session.active_list")
def _(rid, params):
    current = str(params.get("current_session_id") or "")
    with _sessions_lock:
        snapshot = list(_sessions.items())
    rows = [_session_live_item(sid, session, current) for sid, session in snapshot]
    return _ok(rid, {"sessions": rows})
'''

PROMPT_METHODS_SOURCE = '''
def method(name):
    return lambda fn: fn

@method("prompt.submit")
def _(rid, params):
    session = sessions[params["session_id"]]
    if (refusal := _ensure_active_session_slot(params["session_id"], session)) is not None:
        return _err(rid, 4090, str(refusal), {"reason": refusal.reason})
    return _ok(rid, {"ok": True})
'''

ACTIVE_SESSIONS_SOURCE = '''
SESSION_NOT_OWNED = "SESSION_NOT_OWNED"
PER_SESSION_EXCLUSIVE_SUBMIT = True

def session_already_owned_message(session_id, entry):
    return f"Session {session_id} already has a live owner ({entry}). Only one surface at a time may run a session."
'''

API_SOURCE = '''
ROUTES = [
    ("POST", "/api/sessions/{session_id}/chat/stream"),
    ("POST", "/v1/chat/completions"),
    ("POST", "/v1/runs"),
    ("GET", "/v1/runs/{run_id}/events"),
]

async def _handle_session_chat_stream(request):
    return ("assistant.completed", "run.completed", "done")

async def _handle_chat_completions(request):
    return "openai"

async def _handle_runs(request):
    return ("message.delta", "run.completed")
'''



# Minimal input programs for the source analyzer, not substitute upstream evidence.
HOSTED_METHODS_SOURCE = """
_METHODS = ("groups.capabilities", "groups.list", "groups.state", "groups.send", "groups.log")
@method("groups.capabilities")
def capabilities(rid, params, _methods=_METHODS):
    service = get_hosted_room_service()
    driver_ready = bool(service and service.runtime.status()["running"])
    return _ok(rid, {"protocol_version": PROTOCOL_VERSION, "driver": driver_ready,
        "authority_gateway_id": local_authority_gateway_id(),
        "features": ["room_identity", "monotonic_log", "idempotent_send", "typed_events", "actor_identity"],
        "methods": list(_methods), "max_log_limit": MAX_LOG_LIMIT})
@_room_method("groups.send", code=5112, room_code=4111, service_code=4123)
def send(rid, params, service):
    client_event_id = params.get("event_id")
    event = service.send(room_id=params.get("room_id"), event_id=user_event_id(client_event_id), payload=params.get("payload"))
    return _ok(rid, {"event": event, "client_event_id": client_event_id, "accepted": True, "driver_started": True})
@_room_method("groups.state", code=5115, room_code=4114, db=True)
def state(rid, params, db_path):
    room = room_state(db_path, room_id=params.get("room_id"), include_disbanded=params.get("include_disbanded") is True)
    result = {"room": room}
    service = get_hosted_room_service()
    if service is not None and room.get("disbanded_at") is None:
        result["driver_status"] = service.status_with_grant_fingerprints(str(room["room_id"]))
    return _ok(rid, result)
@_room_method("groups.list", code=5110, db=True)
def list_rooms_rpc(rid, params, db_path):
    limit = params.get("limit", MAX_ROOM_LIST_LIMIT)
    offset = params.get("offset", 0)
    rooms = list_rooms(db_path, limit=limit, offset=offset)
    return _ok(rid, {"rooms": rooms, "next_offset": offset + limit if len(rooms) == limit else None})
def _passthrough(name, module, fn_name, doc, *, code, room_code, params, wrap=None):
    @_room_method(name, code=code, room_code=room_code, db=True)
    def handler(rid, params_in, db_path, _import=importlib.import_module):
        kwargs = {(spec if isinstance(spec, str) else spec[0]):
            (params_in.get(spec) if isinstance(spec, str) else spec[1](params_in)) for spec in params}
        result = getattr(_import(module), fn_name)(db_path, **kwargs)
        return _ok(rid, {wrap: result} if wrap else result)
_passthrough("groups.log", "gateway.hosted_rooms", "read_events", "Read log",
    code=5113, room_code=4112, params=("room_id", ("since_seq", lambda p: p.get("since_seq", 0)),
    ("limit", lambda p: p.get("limit", 100)), ("include_disbanded", _include_disbanded)))
"""
HOSTED_STORAGE_SOURCE = """
def user_event_id(client_event_id):
    return f"user:{hashlib.sha256(_event_id(client_event_id).encode('utf-8')).hexdigest()}"
def append_event(db_path, room_id, event_id, kind, actor_json, authority_epoch, payload_json):
    with _transaction(db_path, immediate=True) as conn:
        existing = _load_event(conn, room_id, event_id)
        if existing is not None:
            if _event_content(existing) != (kind, actor_json, authority_epoch, payload_json):
                raise EventConflictError("different content")
            return _event_from_row(existing, idempotent=True)
        _insert_event(conn, room_id, event_id)
def read_events(db_path, room_id, since_seq=0, limit=100):
    since_seq = _non_negative(since_seq, "since_seq")
    limit = _bounded_limit(limit, MAX_LOG_LIMIT)
    rows = conn.execute("SELECT * FROM hosted_room_events WHERE room_id=? AND seq>? ORDER BY seq ASC LIMIT ?", (room_id, since_seq, limit)).fetchall()
    events = [_event_from_row(row) for row in rows]
    cursor = events[-1]["seq"] if events else since_seq
    return {"events": events, "cursor": cursor, "latest_seq": latest_seq,
            "has_more": cursor < latest_seq, "authority": {"gateway_id": gateway_id, "epoch": epoch}}
"""
HOSTED_SERVICE_SOURCE = """
def send(self, *, room_id, event_id, payload):
    return self.send_server_owned(room_id=room_id, event_id=event_id, payload=payload, actor={"kind": "user", "id": "desktop"})
def send_server_owned(self, *, room_id, event_id, payload, actor):
    if isinstance(payload, Mapping) and "thread_id" not in payload:
        payload = {**payload, "thread_id": event_id}
    normalized = discussion.validate_user_payload(payload, member_ids=member_ids)
    event = hosted_rooms.append_event(self.db_path, room_id=room_id, event_id=event_id,
        kind="message.user", actor=dict(actor), payload=normalized,
        authority_gateway_id=str(room["authority_gateway_id"]), authority_epoch=int(room["authority_epoch"]))
    return event
"""
HOSTED_DISCUSSION_SOURCE = """
def validate_user_payload(value, *, member_ids=None):
    payload = _exact_fields(value, label="user payload", required=_USER_PAYLOAD_FIELDS, optional={"attachments"})
    return {"text": payload["text"].strip(), "thread_id": _identifier(payload["thread_id"], label="thread_id")}
def resolve_mentions(texts, members, *, default_all=True):
    by_handle = {member.handle.casefold(): member for member in members}
    mentioned = set()
    for text in texts:
        for match in _MENTION_RE.finditer(str(text or "")):
            handle = match.group(1).casefold()
            if handle in by_handle:
                mentioned.add(handle)
    return tuple(member for member in members if member.handle.casefold() in mentioned)
"""

class GatewayScenarioConformanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        sources = {
            module.SERVER: SERVER_SOURCE,
            module.SESSION_METHODS: METHODS_SOURCE,
            module.PROMPT_METHODS: PROMPT_METHODS_SOURCE,
            module.ACTIVE_SESSIONS: ACTIVE_SESSIONS_SOURCE,
            module.API_SERVER: API_SOURCE,
        }
        for relative, text in sources.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_hosted_manifest_is_opt_in_without_changing_standard_defaults(self):
        required = ("gateway.groups_capabilities", "gateway.groups_send_idempotent", "gateway.groups_state_log")
        manifest = self.root / "hosted.json"
        manifest.write_text(json.dumps({"contract_requirements": list(required)}), encoding="utf-8")
        try:
            actual = module.load_requirements(manifest)
        except ValueError as exc:
            self.fail(f"hosted contracts are not selectable: {exc}")
        self.assertEqual(required, actual)
        self.assertTrue(set(required).isdisjoint(module.load_requirements(None)))

    def write_hosted_sources(self):
        for relative, source in {
            module.GROUPS_METHODS: HOSTED_METHODS_SOURCE,
            module.HOSTED_ROOMS: HOSTED_STORAGE_SOURCE,
            module.HOSTED_SERVICE: HOSTED_SERVICE_SOURCE,
            module.HOSTED_DISCUSSION: HOSTED_DISCUSSION_SOURCE,
        }.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")

    def test_hosted_checks_trace_registered_handlers_to_send_and_log_storage(self):
        self.write_hosted_sources()
        try:
            results = module.audit_sources(self.root, module.HOSTED_CONTRACTS)
        except (KeyError, ValueError) as exc:
            self.fail(f"hosted audit not implemented: {exc}")
        self.assertEqual(set(module.HOSTED_CONTRACTS), {result.contract for result in results})
        self.assertTrue(all(result.passed and result.evidence for result in results), results)

    def test_hosted_checks_fail_on_wire_or_durability_drift(self):
        cases = [
            (module.GROUPS_METHODS, HOSTED_METHODS_SOURCE, '"accepted": True', '"accepted": False', module.GROUPS_SEND),
            (module.GROUPS_METHODS, HOSTED_METHODS_SOURCE, 'payload=params.get("payload")', 'payload=params.get("text")', module.GROUPS_SEND),
            (module.HOSTED_ROOMS, HOSTED_STORAGE_SOURCE, 'idempotent=True', 'idempotent=False', module.GROUPS_SEND),
            (module.GROUPS_METHODS, HOSTED_METHODS_SOURCE, '"driver": driver_ready', '"driver": True', module.GROUPS_CAPABILITIES),
            (module.GROUPS_METHODS, HOSTED_METHODS_SOURCE, '"read_events", "Read log"', '"other_events", "Read log"', module.GROUPS_STATE_LOG),
            (module.HOSTED_ROOMS, HOSTED_STORAGE_SOURCE, 'seq>?', 'seq>=?', module.GROUPS_STATE_LOG),
        ]
        for relative, source, old, new, contract in cases:
            with self.subTest(contract=contract, mutation=new):
                self.write_hosted_sources()
                (self.root / relative).write_text(source.replace(old, new), encoding="utf-8")
                result, = module.audit_sources(self.root, (contract,))
                self.assertFalse(result.passed, result)
                self.assertTrue(result.problem)

    def test_dependency_manifest_cannot_bypass_clean_vanilla_checkout_checks(self):
        module._git(self.root, "init")
        module._git(self.root, "add", ".")
        module._git(self.root, "-c", "user.name=Contract Fixture", "-c", "user.email=fixture@example.invalid",
                    "-c", "commit.gpgsign=false", "commit", "-m", "Synthetic analyzer input")
        with self.assertRaisesRegex(ValueError, "non-vanilla"):
            module.inspect_checkout(self.root)
        module._git(self.root, "remote", "add", "upstream", "https://github.com/NousResearch/hermes-agent.git")
        self.assertTrue(module.inspect_checkout(self.root))
        (self.root / "hosted.json").write_text(json.dumps({
            "contract_requirements": list(module.HOSTED_CONTRACTS),
            "conformance_dependency": {"status": "dependency_gated"},
        }), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "dirty"):
            module.inspect_checkout(self.root)

    def test_missing_hosted_dependency_fails_without_affecting_standard_checks(self):
        results = module.audit_sources(self.root, module.HOSTED_CONTRACTS)
        self.assertTrue(all(not result.passed and "missing upstream source" in result.problem for result in results))
        self.assertTrue(all(result.passed for result in module.audit_sources(self.root, module.ALL_CONTRACTS)))

    def test_complete_contract_fixture_passes(self):
        results = module.audit_sources(self.root, module.ALL_CONTRACTS)

        self.assertEqual(module.ALL_CONTRACTS, tuple(result.contract for result in results))
        self.assertTrue(all(result.passed for result in results), results)

    def test_missing_terminal_emit_fails_only_terminal_contract(self):
        path = self.root / module.SERVER
        path.write_text(
            SERVER_SOURCE.replace('"message.complete"', '"message.delta"'),
            encoding="utf-8",
        )

        results = module.audit_sources(
            self.root, (module.GATEWAY_TERMINAL, module.GATEWAY_SETTLED_INFO)
        )

        self.assertFalse(results[0].passed)
        self.assertIn("message.complete", results[0].problem)
        self.assertTrue(results[1].passed)

    def test_settlement_must_clear_running_before_info(self):
        path = self.root / module.SERVER
        reordered = SERVER_SOURCE.replace(
            '        session["running"] = False\n'
            '        _emit_settled_session_info(sid, session, agent)',
            '        _emit_settled_session_info(sid, session, agent)\n'
            '        session["running"] = False',
        )
        path.write_text(reordered, encoding="utf-8")

        result = module.audit_sources(self.root, (module.GATEWAY_SETTLED_INFO,))[0]

        self.assertFalse(result.passed)
        self.assertIn("before settled", result.problem)

    def test_manifest_selects_known_contract_subset(self):
        manifest = self.root / "scenario.json"
        manifest.write_text(
            json.dumps(
                {
                    "contract_requirements": [
                        module.SESSION_ACTIVATE,
                        module.GATEWAY_SETTLED_INFO,
                    ]
                }
            ),
            encoding="utf-8",
        )

        requirements = module.load_requirements(manifest)

        self.assertEqual((module.GATEWAY_SETTLED_INFO, module.SESSION_ACTIVATE), requirements)

    def test_active_list_requires_waiting_to_outrank_working(self):
        path = self.root / module.SERVER
        reordered = SERVER_SOURCE.replace(
            '    if _session_pending_kind(sid):\n'
            '        return "waiting"\n'
            '    ready = session.get("agent_ready")',
            '    if session.get("running"):\n'
            '        return "working"\n'
            '    if _session_pending_kind(sid):\n'
            '        return "waiting"\n'
            '    ready = session.get("agent_ready")',
        )
        path.write_text(reordered, encoding="utf-8")

        result = module.audit_sources(self.root, (module.SESSION_ACTIVE_LIST,))[0]

        self.assertFalse(result.passed)
        self.assertIn("precedence", result.problem)

    def test_manifest_rejects_unknown_contract(self):
        manifest = self.root / "scenario.json"
        manifest.write_text(json.dumps({"requires": ["relay.private_route"]}), encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "unknown scenario contract"):
            module.load_requirements(manifest)

    def test_fork_markers_are_rejected(self):
        path = self.root / module.API_SERVER
        path.write_text(API_SOURCE + '\nFORK = "RelayPlugin"\n', encoding="utf-8")

        with self.assertRaisesRegex(ValueError, "fork marker"):
            module.audit_sources(self.root, module.ALL_CONTRACTS)


if __name__ == "__main__":
    unittest.main()

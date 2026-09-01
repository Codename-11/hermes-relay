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

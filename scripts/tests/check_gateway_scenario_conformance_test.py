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

def _run_prompt_submit(sid, session, agent):
    try:
        _emit("message.complete", sid, {})
    finally:
        session["running"] = False
        _emit_settled_session_info(sid, session, agent)
'''

METHODS_SOURCE = '''
def method(name):
    return lambda fn: fn

@method("session.resume")
def _(rid, params):
    target = params.get("session_id", "")
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

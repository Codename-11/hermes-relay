#!/usr/bin/env python3
"""Exercise the unmodified upstream request registry without starting a Gateway/provider."""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path


def main() -> None:
    root = Path(sys.argv[1]).resolve()
    spec = importlib.util.spec_from_file_location(
        "conformance", Path(__file__).with_name("check-gateway-scenario-conformance.py"))
    assert spec and spec.loader
    checker = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = checker
    spec.loader.exec_module(checker)
    sha = checker.inspect_checkout(root)
    assert checker.audit_sources(root, [checker.SERVER_REQUESTS])[0].passed
    sys.path.insert(0, str(root))
    from tui_gateway import server_requests as sr
    from tui_gateway.contracts import SERVER_REQUESTS

    frames, events, results = [], [], []
    peer = object()
    action = lambda frame: None

    def write(frame):
        frames.append(frame)
        action(frame)

    sr.reset_for_tests()
    sr.bind_sinks(write, lambda *event: events.append(event), lambda sid: sr.answers_requests(peer))
    assert sr.send("clarify", "session", {"question": "Choose?"}, timeout=0) is None
    assert not frames
    sr.advertise(peer, True)

    for method, params, result in (
        ("clarify", {"question": "Choose?"}, {"answer": ""}),
        ("sudo", {"command": "echo fixture"}, {"value": "fixture-password"}),
        ("secret", {"env_var": "FIXTURE", "prompt": "Value?"}, {"value": ""}),
    ):
        action = lambda f, result=result: sr.resolve_response({"id": f["id"], "result": result})
        assert sr.send(method, "session", params, timeout=0) == result
        assert frames[-1]["params"]["session_id"] == "session"
        assert frames[-1]["id"].startswith("srq-")
        assert not sr.open_requests("session")
        results.append(method)

    action = lambda f: sr.resolve_response({"id": f["id"], "result": {"choice": "deny"}})
    approval = []
    sr.send_async("approval", "session", {"request_id": "queue-1", "command": "echo fixture"}, approval.append)
    assert approval == [{"choice": "deny"}]

    questions = [{"qid": q, "question": q} for q in ("a", "b")]

    def lock_first(frame):
        assert sr.lock_answer(frame["id"], "a", "first") == ["b"]
        assert sr.open_requests("session")[0]["params"]["answers"] == {"a": "first"}

    action = lock_first
    assert sr.send("clarify", "session", {"questions": questions}, timeout=0, qids=["a", "b"]) == {
        "answers": {"a": "first"}, "timed_out": True}
    assert events[-1][0] == "request.cancel" and events[-1][2]["reason"] == "timeout"

    def lock_all(frame):
        lock_first(frame)
        assert sr.lock_answer(frame["id"], "b", "") == []

    action = lock_all
    assert sr.send("clarify", "session", {"questions": questions}, timeout=0, qids=["a", "b"]) == {
        "answers": {"a": "first", "b": ""}}
    action = lambda f: sr.resolve_response({"id": f["id"], "result": {}})
    assert sr.send("clarify", "session", {"questions": questions}, timeout=0, qids=["a", "b"]) == {}

    action = lambda f: sr.resolve_response({"id": f["id"], "error": {"code": -32601, "message": "Unsupported"}})
    assert sr.send("tour", "session", {"action": "start"}, timeout=0) is None
    sr.forget(peer)
    count = len(frames)
    assert sr.send("clarify", "session", {"question": "Choose?"}, timeout=0) is None
    assert len(frames) == count
    sr.advertise(peer, True)
    action = lambda f: sr.resolve_response({"id": f["id"], "result": {"answer": "reconnected"}})
    assert sr.send("clarify", "session", {"question": "Choose?"}, timeout=0) == {"answer": "reconnected"}
    sr.reset_for_tests()
    assert checker.inspect_checkout(root) == sha
    print(json.dumps({"upstream_sha": sha, "passed": True, "provider_calls": False,
                      "methods": sorted(SERVER_REQUESTS),
                      "checks": ["capabilities", "renegotiation", "response shapes", "approval",
                                 "partial timeout", "lock replay", "last lock", "skip", "cancel-all", "unsupported"]}, indent=2))


if __name__ == "__main__":
    main()

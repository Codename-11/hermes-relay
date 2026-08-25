"""Tests for Relay's fail-open Gateway lifecycle hooks."""

from __future__ import annotations

import sys
import threading
import unittest
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest import mock

from plugin.hooks import capture_active_credential, register_hooks


class RelayHookTests(unittest.TestCase):
    def test_capture_records_only_stable_identity_for_exact_session(self) -> None:
        agent = SimpleNamespace(
            _credential_pool_entry_id="entry-2",
            _credential_pool=SimpleNamespace(provider="openai-codex"),
        )
        gateway_server = SimpleNamespace(
            _sessions={
                "gateway-ui-2": {
                    "agent": agent,
                    "session_key": "session-2",
                    "profile_home": "/profiles/victor",
                }
            },
            _sessions_lock=threading.Lock(),
        )
        tui_gateway = ModuleType("tui_gateway")
        tui_gateway.server = gateway_server

        with (
            mock.patch.dict(sys.modules, {"tui_gateway": tui_gateway}),
            mock.patch(
                "plugin.relay.active_credentials.record_active_credential_aliases"
            ) as record,
        ):
            capture_active_credential(session_id="session-2")

        record.assert_called_once_with(
            Path("/profiles/victor").resolve(),
            session_ids={"session-2", "gateway-ui-2"},
            provider_id="openai-codex",
            credential_id="entry-2",
        )
        self.assertNotIn("api_key", record.call_args.kwargs)

    def test_registration_keeps_older_hosts_working(self) -> None:
        registered: list[str] = []

        def register(name, _callback):
            if name != "on_session_start":
                raise ValueError("unknown hook")
            registered.append(name)

        register_hooks(SimpleNamespace(register_hook=register))
        self.assertEqual(registered, ["on_session_start"])


if __name__ == "__main__":
    unittest.main()

"""Focused tests for shared Desktop tool availability snapshots."""

from __future__ import annotations

import os
import threading
import unittest
from unittest.mock import patch

import requests

from plugin.tools import desktop_tool


class _Response:
    def __init__(self, body, status_code: int = 200) -> None:
        self._body = body
        self.status_code = status_code

    def json(self):
        if isinstance(self._body, Exception):
            raise self._body
        return self._body


def _health(*, connected=True, clients=None, advertised_tools=None):
    body = {
        "connected": connected,
        "advertised_tools": advertised_tools or [],
    }
    if clients is not None:
        body["clients"] = [
            {"device_id": f"pc-{index}", "advertised_tools": tools}
            for index, tools in enumerate(clients)
        ]
    return body


class DesktopToolAvailabilityTests(unittest.TestCase):
    def setUp(self) -> None:
        desktop_tool._clear_availability_cache()

    def tearDown(self) -> None:
        desktop_tool._clear_availability_cache()

    def test_default_relay_url_uses_ipv4_loopback_and_override_is_preserved(self) -> None:
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(desktop_tool._relay_url(), "http://127.0.0.1:8767")
        with patch.dict(os.environ, {"DESKTOP_RELAY_URL": "http://[::1]:9876"}, clear=True):
            self.assertEqual(desktop_tool._relay_url(), "http://[::1]:9876")

    def test_one_health_snapshot_serves_tools_and_desktop_health(self) -> None:
        response = _Response(
            _health(
                clients=[["desktop_read_file", "desktop_health"]],
                advertised_tools=["desktop_read_file", "desktop_health"],
            )
        )
        with patch.object(desktop_tool.requests, "get", return_value=response) as get:
            self.assertTrue(desktop_tool._check_tool("desktop_read_file"))
            self.assertFalse(desktop_tool._check_tool("desktop_terminal"))
            self.assertTrue(desktop_tool._check_relay())
        get.assert_called_once()
        self.assertTrue(get.call_args.args[0].endswith("/desktop/health"))

    def test_multi_client_disjoint_tools_and_legacy_client_match_handler_semantics(self) -> None:
        response = _Response(
            _health(
                clients=[["desktop_read_file"], ["desktop_terminal"], []],
                advertised_tools=["desktop_terminal"],
            )
        )
        with patch.object(desktop_tool.requests, "get", return_value=response):
            self.assertTrue(desktop_tool._check_tool("desktop_read_file"))
            self.assertTrue(desktop_tool._check_tool("desktop_terminal"))
            self.assertTrue(desktop_tool._check_tool("desktop_checksum"))
            self.assertFalse(desktop_tool._check_tool("desktop_computer_status"))

    def test_old_health_response_without_clients_uses_legacy_rules(self) -> None:
        with patch.object(
            desktop_tool.requests,
            "get",
            return_value=_Response(_health(advertised_tools=["desktop_terminal"])),
        ):
            self.assertTrue(desktop_tool._check_tool("desktop_terminal"))
            self.assertFalse(desktop_tool._check_tool("desktop_read_file"))

        desktop_tool._clear_availability_cache()
        with patch.object(
            desktop_tool.requests,
            "get",
            return_value=_Response(_health(advertised_tools=[])),
        ):
            self.assertTrue(desktop_tool._check_tool("desktop_read_file"))
            self.assertFalse(desktop_tool._check_tool("desktop_computer_status"))

    def test_reachable_no_client_keeps_health_available_but_tools_unavailable(self) -> None:
        with patch.object(
            desktop_tool.requests,
            "get",
            return_value=_Response(_health(connected=False, clients=[])),
        ):
            self.assertTrue(desktop_tool._check_relay())
            self.assertFalse(desktop_tool._check_tool("desktop_read_file"))

    def test_unreachable_relay_disables_health_and_tools_from_one_cached_miss(self) -> None:
        with patch.object(
            desktop_tool.requests,
            "get",
            side_effect=requests.ConnectionError("offline"),
        ) as get:
            self.assertFalse(desktop_tool._check_relay())
            self.assertFalse(desktop_tool._check_tool("desktop_read_file"))
        get.assert_called_once()

    def test_malformed_availability_fields_fail_closed(self) -> None:
        malformed = [
            {},
            {"connected": 1, "advertised_tools": [], "clients": []},
            {"connected": False, "advertised_tools": "desktop_read_file", "clients": []},
            {"connected": True, "advertised_tools": [], "clients": "invalid"},
            {"connected": True, "advertised_tools": [], "clients": []},
            {"connected": False, "advertised_tools": ["desktop_read_file"], "clients": []},
            {"connected": True, "advertised_tools": [], "clients": [{}]},
            {"connected": True, "advertised_tools": [], "clients": [["desktop_read_file"]]},
            {"connected": True, "advertised_tools": [], "clients": [{"advertised_tools": [1]}]},
        ]
        for body in malformed:
            with self.subTest(body=body):
                desktop_tool._clear_availability_cache()
                with patch.object(desktop_tool.requests, "get", return_value=_Response(body)):
                    self.assertFalse(desktop_tool._check_relay())
                    self.assertFalse(desktop_tool._check_tool("desktop_read_file"))

    def test_slow_probe_receives_full_ttl_after_it_completes(self) -> None:
        clock = [0.0]

        def get(*args, **kwargs):
            clock[0] = 10.0
            return _Response(_health(connected=False, clients=[]))

        with (
            patch.object(desktop_tool.time, "monotonic", side_effect=lambda: clock[0]),
            patch.object(desktop_tool.requests, "get", side_effect=get) as request,
        ):
            self.assertTrue(desktop_tool._check_relay())
            clock[0] = 12.9
            self.assertTrue(desktop_tool._check_relay())
            self.assertEqual(request.call_count, 1)
            clock[0] = 13.0
            self.assertTrue(desktop_tool._check_relay())
            self.assertEqual(request.call_count, 2)

    def test_endpoint_and_token_changes_do_not_reuse_a_snapshot(self) -> None:
        response = _Response(_health(connected=False, clients=[]))
        with patch.object(desktop_tool.requests, "get", return_value=response) as get:
            with patch.dict(
                os.environ,
                {"DESKTOP_RELAY_URL": "http://127.0.0.1:8767", "DESKTOP_RELAY_TOKEN": "one"},
                clear=True,
            ):
                self.assertTrue(desktop_tool._check_relay())
                self.assertTrue(desktop_tool._check_relay())
            with patch.dict(
                os.environ,
                {"DESKTOP_RELAY_URL": "http://127.0.0.1:8767", "DESKTOP_RELAY_TOKEN": "two"},
                clear=True,
            ):
                self.assertTrue(desktop_tool._check_relay())
            with patch.dict(
                os.environ,
                {"DESKTOP_RELAY_URL": "http://127.0.0.1:9999", "DESKTOP_RELAY_TOKEN": "two"},
                clear=True,
            ):
                self.assertTrue(desktop_tool._check_relay())
        self.assertEqual(get.call_count, 3)

    def test_endpoint_history_is_bounded_and_expired_entries_are_pruned(self) -> None:
        clock = [0.0]
        response = _Response(_health(connected=False, clients=[]))
        with (
            patch.object(desktop_tool.time, "monotonic", side_effect=lambda: clock[0]),
            patch.object(desktop_tool.requests, "get", return_value=response),
        ):
            for port in range(desktop_tool._AVAILABILITY_CACHE_MAX_ENTRIES + 5):
                with patch.dict(
                    os.environ,
                    {"DESKTOP_RELAY_URL": f"http://127.0.0.1:{9000 + port}"},
                    clear=True,
                ):
                    self.assertTrue(desktop_tool._check_relay())

            self.assertEqual(
                len(desktop_tool._availability_cache),
                desktop_tool._AVAILABILITY_CACHE_MAX_ENTRIES,
            )
            self.assertEqual(
                len(desktop_tool._availability_probe_generation),
                desktop_tool._AVAILABILITY_CACHE_MAX_ENTRIES,
            )

            clock[0] = desktop_tool._AVAILABILITY_CACHE_TTL_SECONDS
            with patch.dict(
                os.environ,
                {"DESKTOP_RELAY_URL": "http://127.0.0.1:9999"},
                clear=True,
            ):
                self.assertTrue(desktop_tool._check_relay())
            self.assertEqual(len(desktop_tool._availability_cache), 1)
            self.assertEqual(len(desktop_tool._availability_probe_generation), 1)

    def test_older_concurrent_probe_cannot_overwrite_newer_cache_entry(self) -> None:
        first_started = threading.Event()
        release_first = threading.Event()
        call_count = 0
        call_count_lock = threading.Lock()

        def get(*args, **kwargs):
            nonlocal call_count
            with call_count_lock:
                call_count += 1
                call_number = call_count
            if call_number == 1:
                first_started.set()
                release_first.wait(timeout=2)
                return _Response(
                    _health(clients=[["desktop_read_file"]], advertised_tools=["desktop_read_file"])
                )
            release_first.set()
            return _Response(_health(connected=False, clients=[]))

        results: list[bool] = []
        with patch.object(desktop_tool.requests, "get", side_effect=get):
            first = threading.Thread(
                target=lambda: results.append(desktop_tool._check_tool("desktop_read_file"))
            )
            first.start()
            self.assertTrue(first_started.wait(timeout=2))
            second = threading.Thread(
                target=lambda: results.append(desktop_tool._check_tool("desktop_read_file"))
            )
            second.start()
            first.join(timeout=2)
            second.join(timeout=2)
            self.assertFalse(first.is_alive())
            self.assertFalse(second.is_alive())
            self.assertEqual(call_count, 2)  # Duplicate concurrent probes are allowed.
            self.assertFalse(desktop_tool._check_tool("desktop_read_file"))
        self.assertCountEqual(results, [True, False])


if __name__ == "__main__":
    unittest.main()

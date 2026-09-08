"""Availability checks for ``desktop_*`` tools share one cached health snapshot."""

from __future__ import annotations

import unittest
from unittest.mock import Mock, patch

import requests

from plugin.tools import desktop_tool


def _health(connected: bool, tools: list[str]) -> Mock:
    response = Mock(status_code=200)
    response.json.return_value = {
        "connected": connected,
        "advertised_tools": tools,
    }
    return response


class DesktopAvailabilityCheckTests(unittest.TestCase):
    def setUp(self) -> None:
        desktop_tool._advertised_cache = (0.0, None)

    def tearDown(self) -> None:
        desktop_tool._advertised_cache = (0.0, None)

    def test_sweep_across_all_tools_makes_one_health_request(self) -> None:
        names = [n for n in desktop_tool._SCHEMAS if n not in desktop_tool._RELAY_ONLY_TOOLS]
        self.assertGreater(len(names), 20)
        with patch.object(
            desktop_tool.requests,
            "get",
            return_value=_health(True, ["desktop_read_file", "desktop_powershell"]),
        ) as get:
            results = {name: desktop_tool._check_tool(name) for name in names}
        self.assertEqual(get.call_count, 1)
        self.assertTrue(get.call_args.args[0].endswith("/desktop/health"))
        self.assertTrue(results["desktop_read_file"])
        self.assertTrue(results["desktop_powershell"])
        self.assertFalse(results["desktop_terminal"])
        self.assertFalse(results["desktop_computer_action"])

    def test_unreachable_relay_fails_closed_once_per_window(self) -> None:
        with patch.object(
            desktop_tool.requests,
            "get",
            side_effect=requests.ConnectionError("refused"),
        ) as get:
            self.assertFalse(desktop_tool._check_tool("desktop_read_file"))
            self.assertFalse(desktop_tool._check_tool("desktop_terminal"))
            self.assertFalse(desktop_tool._check_tool("desktop_job_start"))
        self.assertEqual(get.call_count, 1)

    def test_no_client_connected_is_unavailable(self) -> None:
        with patch.object(
            desktop_tool.requests, "get", return_value=_health(False, [])
        ):
            self.assertFalse(desktop_tool._check_tool("desktop_read_file"))

    def test_client_without_advertisement_is_optimistic_except_computer_use(self) -> None:
        # Mirrors DesktopHandler.has_client_for for older clients that never
        # sent a desktop.status advertisement.
        with patch.object(
            desktop_tool.requests, "get", return_value=_health(True, [])
        ):
            self.assertTrue(desktop_tool._check_tool("desktop_read_file"))
            self.assertFalse(desktop_tool._check_tool("desktop_computer_action"))

    def test_cache_expires_after_ttl(self) -> None:
        with patch.object(
            desktop_tool.requests, "get", return_value=_health(True, ["desktop_read_file"])
        ) as get:
            self.assertTrue(desktop_tool._check_tool("desktop_read_file"))
            stamp, cached = desktop_tool._advertised_cache
            desktop_tool._advertised_cache = (
                stamp - desktop_tool._ADVERTISED_CACHE_TTL_S - 1,
                cached,
            )
            self.assertTrue(desktop_tool._check_tool("desktop_read_file"))
        self.assertEqual(get.call_count, 2)

    def test_relay_only_health_check_bypasses_snapshot(self) -> None:
        # desktop_health stays callable with no client connected.
        with patch.object(
            desktop_tool.requests, "get", return_value=_health(False, [])
        ):
            self.assertTrue(desktop_tool._check_relay())
            self.assertFalse(desktop_tool._check_tool("desktop_read_file"))


if __name__ == "__main__":
    unittest.main()

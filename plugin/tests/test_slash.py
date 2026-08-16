"""Focused tests for host-side Relay session management commands."""

from __future__ import annotations

import json
import unittest
from unittest.mock import MagicMock, patch

from plugin import slash


class RelaySlashRevokeTests(unittest.TestCase):
    def test_revoke_requires_token_prefix(self) -> None:
        self.assertIn("at least 4", slash.relay_slash_handler("revoke abc"))

    @patch("plugin.slash.urllib.request.urlopen")
    @patch("plugin.slash._relay_port", return_value=8767)
    def test_revoke_uses_loopback_delete(self, _port: MagicMock, urlopen: MagicMock) -> None:
        response = MagicMock()
        response.read.return_value = json.dumps({"ok": True}).encode("utf-8")
        urlopen.return_value.__enter__.return_value = response

        result = slash.relay_slash_handler("revoke abcdef12")

        request = urlopen.call_args.args[0]
        self.assertEqual(request.get_method(), "DELETE")
        self.assertEqual(request.full_url, "http://127.0.0.1:8767/sessions/abcdef12")
        self.assertEqual(result, "Revoked paired device `abcdef12`.")


if __name__ == "__main__":
    unittest.main()

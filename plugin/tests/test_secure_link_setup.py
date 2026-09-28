from __future__ import annotations

import argparse
import io
import json
import tempfile
import shutil
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

from aiohttp import web

from plugin import cli
from plugin.relay.config import RelayConfig
from plugin.relay.secure_link_setup import secure_link_preflight, setup_address, _certificate_matches
from plugin.relay.secure_proxy import ensure_tls_identity
from plugin.relay.server import _on_secure_proxy_startup, handle_secure_link_preflight


class SetupAddressTests(unittest.TestCase):
    def test_address_is_not_a_command_url_or_unspecified_bind(self) -> None:
        for host in ["", "0.0.0.0", "::", "224.0.0.1", "127.1", "https://relay.example", "a..example", "a;id", "a\nb", "user@host", "fe80::1%eth0;id", "relay.example]"]:
            with self.subTest(host=host), self.assertRaises(ValueError):
                setup_address(host, 9443)
        for port in [0, 65536, True, "12;id", "3.5"]:
            with self.subTest(port=port), self.assertRaises(ValueError):
                setup_address("relay.example", port)
        self.assertEqual(setup_address("[2001:db8::1]", "9443"), ("2001:db8::1", 9443))
        self.assertEqual(setup_address("Relay.Example", 9443), ("relay.example", 9443))


class SecureLinkSetupTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = RelayConfig(hermes_config_path=str(Path(self.temp.name) / "config.yaml"))
        self.server = SimpleNamespace(config=self.config, client_count=2, secure_proxy_candidate=None)
        self.api = self.enterContext(patch("plugin.relay.secure_link_setup._api_available", new=AsyncMock(return_value=True)))
        self.gate = self.enterContext(patch("plugin.relay.secure_link_setup._dashboard_gate_enabled", new=AsyncMock(return_value=True)))
        self.bind = self.enterContext(patch("plugin.relay.secure_link_setup._can_bind", new=AsyncMock(return_value=True)))
        self.enterContext(patch("plugin.relay.secure_link_setup.shutil.which", return_value="openssl"))

    async def report(self, **kwargs):
        return await secure_link_preflight(self.server, **{"host": "192.0.2.10", **kwargs})

    async def test_readiness_does_not_enable_write_keys_or_claim_chat_ready(self) -> None:
        before = vars(self.config).copy()
        result = await self.report()
        self.assertEqual(result["state"], "ready_to_configure")
        self.assertTrue(result["read_only"])
        self.assertFalse(result["pairing_ready"])
        self.assertEqual(result["connected_clients"], 2)
        self.assertEqual(result["activation_mode"], "instructions")
        self.assertEqual(vars(self.config), before)
        self.assertEqual(list(Path(self.temp.name).iterdir()), [])
        self.assertNotIn("key.pem", json.dumps(result))
        self.assertNotIn("certificate_pin", json.dumps(result))

    async def test_wildcard_configuration_requires_an_advertised_address(self) -> None:
        result = await secure_link_preflight(self.server)
        self.assertEqual(result["state"], "needs_attention")
        self.assertEqual(result["environment"], {})
        self.bind.assert_not_awaited()

    async def test_non_loopback_upstream_is_not_probed_or_silently_rewritten(self) -> None:
        self.config.webapi_url = "http://192.0.2.10:8642"
        result = await self.report()
        self.assertFalse(result["ready_to_enable"])
        self.api.assert_not_awaited()
        self.assertEqual(self.config.webapi_url, "http://192.0.2.10:8642")
        self.assertIn("unless that listener actually accepts loopback", next(c["detail"] for c in result["checks"] if c["id"] == "api"))

    async def test_dashboard_auth_required_and_optional_api_failure_are_distinct(self) -> None:
        self.api.return_value = False
        result = await self.report()
        self.assertTrue(result["ready_to_enable"])
        self.assertEqual(next(c["status"] for c in result["checks"] if c["id"] == "api"), "warning")
        self.gate.return_value = False
        result = await self.report()
        self.assertFalse(result["ready_to_enable"])

    async def test_port_ownership_and_active_address_gate_qr_handoff(self) -> None:
        self.bind.return_value = False
        self.assertFalse((await self.report())["pairing_ready"])
        self.server.secure_proxy_candidate = {"proxy": {"url": "https://192.0.2.10:9443"}}
        result = await self.report()
        self.assertEqual(result["state"], "enabled")
        self.assertTrue(result["pairing_ready"])
        changed = await self.report(port=9444)
        self.assertFalse(changed["pairing_ready"])

    async def test_incomplete_or_wrong_host_identity_never_rotates_keys(self) -> None:
        cert = Path(self.temp.name) / "cert.pem"
        key = Path(self.temp.name) / "key.pem"
        cert.write_text("existing public certificate")
        self.config.secure_proxy_cert, self.config.secure_proxy_key = str(cert), str(key)
        self.assertFalse((await self.report())["ready_to_enable"])
        key.write_text("test private material")
        with patch("plugin.relay.secure_link_setup._certificate_matches", new=AsyncMock(return_value=False)):
            report = await self.report()
            self.assertFalse(report["ready_to_enable"])
            self.assertNotIn("test private material", json.dumps(report))
        self.assertEqual(key.read_text(), "test private material")

    async def test_public_relay_caller_cannot_inspect_operator_configuration(self) -> None:
        request = SimpleNamespace(remote="192.0.2.44")
        with self.assertRaises(web.HTTPForbidden):
            await handle_secure_link_preflight(request)

    async def test_bad_secure_link_configuration_does_not_abort_ordinary_relay_startup(self) -> None:
        self.config.webapi_url = "http://192.0.2.10:8642"
        self.config.secure_proxy_cert = "unused-cert"
        self.config.secure_proxy_key = "unused-key"
        self.server.secure_proxy_candidate = {"proxy": {"url": "https://192.0.2.10:9443"}}
        await _on_secure_proxy_startup({"server": self.server})
        self.assertIsNone(self.server.secure_proxy_candidate)


@unittest.skipUnless(shutil.which("openssl"), "OpenSSL required")
class SetupCertificateTests(unittest.IsolatedAsyncioTestCase):
    async def test_existing_certificate_must_match_selected_address(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cert, key = Path(directory) / "cert.pem", Path(directory) / "key.pem"
            ensure_tls_identity(cert, key, "192.0.2.10")
            before = cert.read_bytes(), key.read_bytes()
            self.assertTrue(await _certificate_matches(cert, "192.0.2.10"))
            self.assertFalse(await _certificate_matches(cert, "192.0.2.11"))
            self.assertEqual((cert.read_bytes(), key.read_bytes()), before)


class SecureLinkCliTests(unittest.TestCase):
    def test_cli_reads_shared_report_and_registers_start_flags(self) -> None:
        parser = argparse.ArgumentParser()
        cli.register_relay_cli(parser)
        args = parser.parse_args(["secure-link", "--host", "relay.example", "--port", "9443", "--json"])
        response = MagicMock()
        report = {"schema_version": 1, "checks": [], "ready_to_enable": True, "state": "ready_to_configure", "read_only": True}
        response.__enter__.return_value = io.StringIO(json.dumps(report))
        with patch("urllib.request.urlopen", return_value=response) as request, redirect_stdout(io.StringIO()) as out:
            args.func(args)
        self.assertEqual(json.loads(out.getvalue()), report)
        self.assertEqual(request.call_args.args[0], "http://127.0.0.1:8767/secure-link/preflight?host=relay.example&port=9443")
        start = parser.parse_args(["start", "--secure-link", "--secure-link-host", "relay.example", "--secure-link-port", "9443"])
        self.assertTrue(start.secure_link)
        self.assertEqual(start.secure_link_host, "relay.example")

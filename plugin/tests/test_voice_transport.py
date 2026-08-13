from __future__ import annotations

import ssl
import tempfile
import types
import unittest
import urllib.request
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

from plugin.relay.realtime_agent.providers.openai import _create_aiohttp_websocket
from plugin.voice_lab.providers.openai_realtime import _create_websocket
from plugin.voice_lab.providers.openai_tts import _urlopen_stream
from plugin.voice_lab.transport import (
    header_lines,
    merge_transport_headers,
    resolve_voice_transport_options,
)


class VoiceTransportTests(unittest.TestCase):
    def test_extra_headers_accept_json_and_override_protocol_defaults(self) -> None:
        transport = resolve_voice_transport_options(
            {"extra_headers": '{"Authorization":"Custom token","X-Gateway":"voice"}'},
            base_url="wss://voice.example.test/realtime",
        )

        headers = merge_transport_headers(
            {"Authorization": "Bearer default", "Accept": "application/json"},
            transport,
        )

        self.assertEqual(headers["Authorization"], "Custom token")
        self.assertEqual(headers["X-Gateway"], "voice")
        self.assertIn("X-Gateway: voice", header_lines(headers))

    def test_ssl_verify_false_maps_to_each_transport_without_env_ca(self) -> None:
        with patch.dict("os.environ", {"HERMES_CA_BUNDLE": "ignored.pem"}, clear=True):
            transport = resolve_voice_transport_options(
                {"ssl_verify": "off"},
                base_url="https://voice.example.test/v1",
            )

        self.assertFalse(transport.ssl_verify)
        self.assertFalse(transport.aiohttp_ssl)
        self.assertEqual(transport.websocket_sslopt["cert_reqs"], ssl.CERT_NONE)
        self.assertEqual(transport.urllib_context.verify_mode, ssl.CERT_NONE)

    def test_explicit_ca_precedes_environment_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            explicit = Path(tmp) / "explicit.pem"
            environment = Path(tmp) / "environment.pem"
            explicit.touch()
            environment.touch()
            context = ssl.create_default_context()
            with patch.dict(
                "os.environ",
                {"HERMES_CA_BUNDLE": str(environment)},
                clear=True,
            ), patch(
                "plugin.voice_lab.transport.ssl.create_default_context",
                return_value=context,
            ) as create_context:
                transport = resolve_voice_transport_options(
                    {"ssl_ca_cert": str(explicit)},
                    base_url="https://voice.example.test/v1",
                )

        create_context.assert_called_once_with(cafile=str(explicit))
        self.assertIs(transport.ssl_context, context)

    def test_websocket_client_factory_forwards_ssl_options(self) -> None:
        sslopt = {"cert_reqs": ssl.CERT_NONE, "check_hostname": False}
        create = MagicMock(return_value=object())
        websocket = types.SimpleNamespace(create_connection=create)
        with patch.dict("sys.modules", {"websocket": websocket}):
            _create_websocket(
                "wss://voice.example.test/realtime",
                ["X-Gateway: voice"],
                5.0,
                sslopt=sslopt,
            )

        create.assert_called_once_with(
            "wss://voice.example.test/realtime",
            header=["X-Gateway: voice"],
            timeout=5.0,
            sslopt=sslopt,
        )

    def test_urllib_factory_forwards_ssl_context(self) -> None:
        context = ssl.create_default_context()
        request = urllib.request.Request("https://voice.example.test/v1/audio/speech")
        with patch("urllib.request.urlopen", return_value=object()) as urlopen:
            _urlopen_stream(request, 5.0, context=context)

        urlopen.assert_called_once_with(request, timeout=5.0, context=context)


class AiohttpVoiceTransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_aiohttp_factory_forwards_ssl_context(self) -> None:
        context = ssl.create_default_context()
        ws = AsyncMock()
        session = MagicMock()
        session.ws_connect = AsyncMock(return_value=ws)
        session.close = AsyncMock()
        with patch(
            "plugin.relay.realtime_agent.providers.openai.aiohttp.ClientSession",
            return_value=session,
        ):
            socket = await _create_aiohttp_websocket(
                "wss://voice.example.test/realtime",
                {"X-Gateway": "voice"},
                5.0,
                ssl=context,
            )

        session.ws_connect.assert_awaited_once_with(
            "wss://voice.example.test/realtime",
            headers={"X-Gateway": "voice"},
            heartbeat=20.0,
            ssl=context,
        )
        await socket.close()


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from plugin.voice_lab.auth import (
    VoiceLabAuthError,
    _poll_xai_device_token,
    _request_xai_device_code,
    login_xai_oauth,
    read_xai_oauth_token,
)


class VoiceLabDeviceCodeAuthTests(unittest.TestCase):
    def test_device_code_request_requires_upstream_response_shape(self) -> None:
        with patch(
            "plugin.voice_lab.auth._post_form_response",
            return_value=(200, {"device_code": "device-only"}),
        ), self.assertRaisesRegex(VoiceLabAuthError, "missing fields"):
            _request_xai_device_code(scope="openid")

    def test_poll_handles_pending_and_slow_down_before_success(self) -> None:
        responses = [
            (400, {"error": "authorization_pending"}),
            (400, {"error": "slow_down"}),
            (
                200,
                {
                    "access_token": "access-test",
                    "refresh_token": "refresh-test",
                    "expires_in": 3600,
                    "token_type": "Bearer",
                },
            ),
        ]
        with patch(
            "plugin.voice_lab.auth._post_form_response",
            side_effect=responses,
        ), patch("plugin.voice_lab.auth.time.sleep") as sleep:
            token = _poll_xai_device_token(
                token_endpoint="https://auth.x.ai/oauth2/token",
                device_code="device-test",
                expires_in=60,
                poll_interval=2,
            )

        self.assertEqual(token["access_token"], "access-test")
        self.assertEqual(sleep.call_args_list[0].args, (2,))
        self.assertEqual(sleep.call_args_list[1].args, (3,))

    def test_poll_reports_denied_authorization(self) -> None:
        with patch(
            "plugin.voice_lab.auth._post_form_response",
            return_value=(400, {"error": "access_denied", "error_description": "Denied"}),
        ), self.assertRaisesRegex(VoiceLabAuthError, "Denied"):
            _poll_xai_device_token(
                token_endpoint="https://auth.x.ai/oauth2/token",
                device_code="device-test",
                expires_in=60,
                poll_interval=1,
            )

    def test_poll_reports_expired_authorization(self) -> None:
        with patch(
            "plugin.voice_lab.auth._post_form_response",
            return_value=(400, {"error": "expired_token"}),
        ), self.assertRaisesRegex(VoiceLabAuthError, "expired_token"):
            _poll_xai_device_token(
                token_endpoint="https://auth.x.ai/oauth2/token",
                device_code="device-test",
                expires_in=60,
                poll_interval=1,
            )

    def test_poll_times_out_after_pending_authorization(self) -> None:
        with patch(
            "plugin.voice_lab.auth._post_form_response",
            return_value=(400, {"error": "authorization_pending"}),
        ), patch(
            "plugin.voice_lab.auth.time.monotonic",
            side_effect=[0.0, 0.0, 2.0],
        ), patch(
            "plugin.voice_lab.auth.time.sleep",
        ), self.assertRaisesRegex(VoiceLabAuthError, "Timed out"):
            _poll_xai_device_token(
                token_endpoint="https://auth.x.ai/oauth2/token",
                device_code="device-test",
                expires_in=1,
                poll_interval=1,
            )

    def test_login_writes_device_code_store_compatible_with_existing_reader(self) -> None:
        device = {
            "device_code": "device-test",
            "user_code": "ABCD-EFGH",
            "verification_uri": "https://accounts.x.ai/device",
            "verification_uri_complete": "https://accounts.x.ai/device?code=ABCD-EFGH",
            "expires_in": 600,
            "interval": 2,
        }
        tokens = {
            "access_token": "access-test",
            "refresh_token": "refresh-test",
            "expires_in": 3600,
            "expires_at_ms": 9999999999999,
            "token_type": "Bearer",
        }
        with tempfile.TemporaryDirectory() as tmp:
            auth_file = Path(tmp) / "xai-oauth.json"
            with patch(
                "plugin.voice_lab.auth._xai_oauth_discovery",
                return_value={"token_endpoint": "https://auth.x.ai/oauth2/token"},
            ), patch(
                "plugin.voice_lab.auth._request_xai_device_code",
                return_value=device,
            ), patch(
                "plugin.voice_lab.auth._poll_xai_device_token",
                return_value=tokens,
            ) as poll, patch("builtins.print"), patch("webbrowser.open") as browser:
                result = login_xai_oauth(
                    auth_file=auth_file,
                    no_browser=True,
                    timeout_seconds=180,
                )
            store = json.loads(auth_file.read_text(encoding="utf-8"))
            resolved = read_xai_oauth_token(auth_file=auth_file, refresh=False)

        self.assertEqual(store["auth_type"], "oauth_device_code")
        self.assertEqual(resolved.access_token, "access-test")
        self.assertEqual(result.token_type, "Bearer")
        browser.assert_not_called()
        self.assertEqual(poll.call_args.kwargs["device_code"], "device-test")


if __name__ == "__main__":
    unittest.main()

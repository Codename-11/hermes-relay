"""Screenshot token retrieval and legacy payload safety checks."""

import base64
from unittest import mock

import pytest
import requests

from plugin.tools.android_screenshot_media import (
    MAX_SCREENSHOT_BYTES,
    ScreenshotMediaError,
    resolve_screenshot,
)


PNG = b"\x89PNG\r\n\x1a\nimage-bytes"
TOKEN = "MEDIA:hermes-relay://valid-token-123456"


def response(status=200, body=PNG, content_type="image/png", length=None):
    value = mock.Mock(spec=requests.Response)
    value.status_code = status
    value.headers = {"Content-Type": content_type}
    if length is not None:
        value.headers["Content-Length"] = str(length)
    value.iter_content.return_value = iter([body])
    return value


def test_token_bytes_and_authenticated_route():
    fetched = response()
    request = mock.Mock(return_value=fetched)
    assert resolve_screenshot({"data": {"media": TOKEN}}, request, 3) == (PNG, "image/png", TOKEN)
    request.assert_called_once_with("GET", "/media/valid-token-123456", timeout=3, stream=True)
    fetched.close.assert_called_once_with()


@pytest.mark.parametrize("status", [401, 403, 404, 500])
def test_fetch_failure_is_safe(status):
    fetched = response(status=status)
    with pytest.raises(ScreenshotMediaError, match="unavailable or access denied") as error:
        resolve_screenshot({"media": TOKEN}, mock.Mock(return_value=fetched), 3)
    assert "valid-token" not in str(error.value)
    fetched.close.assert_called_once_with()


@pytest.mark.parametrize("marker", ["", "MEDIA:hermes-relay://../x", "MEDIA:https://other/x", 5])
def test_invalid_marker_does_not_fetch(marker):
    request = mock.Mock()
    with pytest.raises(ScreenshotMediaError, match="Invalid screenshot media token"):
        resolve_screenshot({"media": marker}, request, 3)
    request.assert_not_called()


def test_stream_cap_and_type_check():
    fetched = response(body=PNG, length=MAX_SCREENSHOT_BYTES + 1)
    with pytest.raises(ScreenshotMediaError, match="size limit"):
        resolve_screenshot({"media": TOKEN}, mock.Mock(return_value=fetched), 3)
    fetched = response(body=PNG, content_type="image/jpeg")
    with pytest.raises(ScreenshotMediaError, match="type mismatch"):
        resolve_screenshot({"media": TOKEN}, mock.Mock(return_value=fetched), 3)
    fetched = response(body=b"x" * (MAX_SCREENSHOT_BYTES + 1))
    with pytest.raises(ScreenshotMediaError, match="size limit"):
        resolve_screenshot({"media": TOKEN}, mock.Mock(return_value=fetched), 3)


def test_legacy_inline_is_bounded_and_validated():
    request = mock.Mock()
    assert resolve_screenshot({"image": base64.b64encode(PNG).decode()}, request, 3) == (PNG, "image/png", None)
    request.assert_not_called()
    with pytest.raises(ScreenshotMediaError, match="Invalid screenshot image data"):
        resolve_screenshot({"image": "%%%"}, request, 3)
    with pytest.raises(ScreenshotMediaError, match="size limit"):
        resolve_screenshot({"image": "A" * (MAX_SCREENSHOT_BYTES * 2)}, request, 3)

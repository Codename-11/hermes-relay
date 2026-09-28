"""Decode the Android bridge screenshot contract for host-side tools."""

from __future__ import annotations

import base64
import binascii
import re
from typing import Any, Callable

import requests


# Keep tool-result images well below the relay's configurable upload cap.
MAX_SCREENSHOT_BYTES = 8 * 1024 * 1024
_TOKEN_RE = re.compile(r"MEDIA:hermes-relay://([A-Za-z0-9_-]{16,128})\Z")


class ScreenshotMediaError(ValueError):
    """A screenshot could not be safely resolved."""


def _image_type(data: bytes) -> str:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    raise ScreenshotMediaError("Screenshot is not a PNG or JPEG image")


def resolve_screenshot(
    raw: dict[str, Any],
    request: Callable[..., requests.Response],
    timeout: float,
) -> tuple[bytes, str, str | None]:
    """Return verified image bytes, MIME type, and optional relay marker.

    Token fetches use the caller's existing bridge bearer and URL; the token
    never becomes a URL or path supplied by the phone. Legacy inline images
    remain readable, but are bounded before decoding.
    """
    if not isinstance(raw, dict):
        raise ScreenshotMediaError("Invalid screenshot response")
    if "error" in raw:
        raise ScreenshotMediaError("Bridge screenshot failed")
    result = raw.get("data", raw)
    if not isinstance(result, dict):
        raise ScreenshotMediaError("Invalid screenshot response")

    marker = result.get("media")
    if marker is not None:
        if not isinstance(marker, str) or not (match := _TOKEN_RE.fullmatch(marker)):
            raise ScreenshotMediaError("Invalid screenshot media token")
        try:
            response = request("GET", f"/media/{match.group(1)}", timeout=timeout, stream=True)
        except requests.RequestException:
            raise ScreenshotMediaError("Screenshot media fetch failed") from None
        try:
            if response.status_code != 200:
                raise ScreenshotMediaError("Screenshot media unavailable or access denied")
            declared = response.headers.get("Content-Type", "").split(";", 1)[0].lower()
            if declared not in {"image/png", "image/jpeg"}:
                raise ScreenshotMediaError("Screenshot media has an unsupported type")
            length = response.headers.get("Content-Length")
            if length:
                try:
                    declared_length = int(length)
                except ValueError as exc:
                    raise ScreenshotMediaError("Invalid screenshot media length") from exc
                if declared_length < 0 or declared_length > MAX_SCREENSHOT_BYTES:
                    raise ScreenshotMediaError("Screenshot exceeds size limit")
            chunks: list[bytes] = []
            size = 0
            for chunk in response.iter_content(chunk_size=64 * 1024):
                size += len(chunk)
                if size > MAX_SCREENSHOT_BYTES:
                    raise ScreenshotMediaError("Screenshot exceeds size limit")
                chunks.append(chunk)
            data = b"".join(chunks)
            if _image_type(data) != declared:
                raise ScreenshotMediaError("Screenshot media type mismatch")
            return data, declared, marker
        except requests.RequestException:
            raise ScreenshotMediaError("Screenshot media fetch failed") from None
        finally:
            response.close()

    encoded = result.get("image")
    if not isinstance(encoded, str) or not encoded:
        raise ScreenshotMediaError("No image data returned")
    if len(encoded) > (MAX_SCREENSHOT_BYTES + 2) // 3 * 4:
        raise ScreenshotMediaError("Screenshot exceeds size limit")
    try:
        data = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ScreenshotMediaError("Invalid screenshot image data") from exc
    if len(data) > MAX_SCREENSHOT_BYTES:
        raise ScreenshotMediaError("Screenshot exceeds size limit")
    return data, _image_type(data), None

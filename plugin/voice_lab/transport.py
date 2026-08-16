"""Transport policy shared by Relay-owned voice provider clients.

The normal Hermes chat clients inherit upstream custom-provider TLS and header
handling. Voice Lab and Realtime Agent open provider sockets directly, so they
mirror the same explicit option names and CA environment precedence here
without reading or coupling to Hermes' ``custom_providers`` configuration.
"""

from __future__ import annotations

import json
import logging
import os
import ssl
import urllib.parse
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .providers.base import ProviderUnavailable

logger = logging.getLogger("hermes_relay.voice_transport")

_CA_ENV_VARS = (
    "HERMES_CA_BUNDLE",
    "SSL_CERT_FILE",
    "REQUESTS_CA_BUNDLE",
    "CURL_CA_BUNDLE",
)


@dataclass(frozen=True, slots=True)
class VoiceTransportOptions:
    """Resolved headers and TLS policy for one provider connection."""

    extra_headers: dict[str, str]
    ssl_context: ssl.SSLContext | None = None
    ssl_verify: bool = True

    @property
    def aiohttp_ssl(self) -> ssl.SSLContext | bool | None:
        if not self.ssl_verify:
            return False
        return self.ssl_context

    @property
    def websocket_sslopt(self) -> dict[str, Any] | None:
        if not self.ssl_verify:
            return {"cert_reqs": ssl.CERT_NONE, "check_hostname": False}
        if self.ssl_context is not None:
            return {"context": self.ssl_context}
        return None

    @property
    def urllib_context(self) -> ssl.SSLContext | None:
        if not self.ssl_verify:
            return ssl._create_unverified_context()  # noqa: SLF001 - stdlib API
        return self.ssl_context


def resolve_voice_transport_options(
    options: dict[str, Any],
    *,
    base_url: str,
) -> VoiceTransportOptions:
    """Resolve Relay voice transport options using upstream-compatible names.

    ``extra_headers`` accepts a mapping or a JSON object string so the
    standalone CLI's repeatable ``--provider-option KEY=VALUE`` syntax remains
    useful. Header values may contain credentials and must never be logged.
    """

    extra_headers = _normalize_extra_headers(options.get("extra_headers"))
    if _verification_disabled(options.get("ssl_verify")):
        logger.warning(
            "TLS certificate verification DISABLED (ssl_verify: false) for %s; "
            "this is intended for local development only",
            _safe_endpoint_label(base_url),
        )
        return VoiceTransportOptions(extra_headers=extra_headers, ssl_verify=False)

    effective_ca = _string_option(options.get("ssl_ca_cert"))
    if not effective_ca:
        effective_ca = next(
            (value for name in _CA_ENV_VARS if (value := os.getenv(name, "").strip())),
            "",
        )
    if not effective_ca:
        return VoiceTransportOptions(extra_headers=extra_headers)

    ca_path = Path(effective_ca).expanduser()
    if not ca_path.is_file():
        logger.warning(
            "Voice provider CA bundle path does not exist: %s; using default certificates",
            ca_path,
        )
        return VoiceTransportOptions(extra_headers=extra_headers)
    return VoiceTransportOptions(
        extra_headers=extra_headers,
        ssl_context=ssl.create_default_context(cafile=str(ca_path)),
    )


def merge_transport_headers(
    defaults: dict[str, str],
    transport: VoiceTransportOptions,
) -> dict[str, str]:
    """Apply explicit provider headers after protocol defaults, as upstream does."""

    merged = dict(defaults)
    merged.update(transport.extra_headers)
    return merged


def header_lines(headers: dict[str, str]) -> list[str]:
    """Convert a header mapping to websocket-client's line representation."""

    return [f"{name}: {value}" for name, value in headers.items()]


def _normalize_extra_headers(value: Any) -> dict[str, str]:
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return {}
        try:
            value = json.loads(text)
        except json.JSONDecodeError as exc:
            raise ProviderUnavailable("extra_headers must be a JSON object") from exc
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise ProviderUnavailable("extra_headers must be a mapping or JSON object")
    return {str(key): str(item) for key, item in value.items() if item is not None}


def _verification_disabled(value: Any) -> bool:
    if value is False:
        return True
    return isinstance(value, str) and value.strip().lower() in {"false", "0", "no", "off"}


def _string_option(value: Any) -> str:
    return str(value).strip() if value is not None else ""


def _safe_endpoint_label(url: str) -> str:
    try:
        parsed = urllib.parse.urlsplit(url)
    except ValueError:
        return "a voice provider endpoint"
    if not parsed.scheme or not parsed.hostname:
        return "a voice provider endpoint"
    port = f":{parsed.port}" if parsed.port is not None else ""
    return f"{parsed.scheme}://{parsed.hostname}{port}"

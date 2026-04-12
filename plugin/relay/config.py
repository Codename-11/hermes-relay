"""Relay configuration — loaded from environment variables and config files."""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

logger = logging.getLogger(__name__)

# Characters used for pairing codes. Full A-Z + 0-9 alphabet (36 chars) so
# codes the phone generates with AuthManager.PAIRING_CODE_CHARS always
# validate cleanly. The earlier "no ambiguous 0/O/1/I" restriction only
# mattered when a human had to retype the code from a display; when the
# phone is the source of truth, that restriction silently rejected valid
# codes containing any of those four characters.
PAIRING_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
PAIRING_CODE_LENGTH = 6


@dataclass
class RelayConfig:
    """Configuration for the relay server."""

    host: str = "0.0.0.0"
    port: int = 8767
    ssl_cert: str | None = None
    ssl_key: str | None = None
    webapi_url: str = "http://localhost:8642"
    hermes_config_path: str = "~/.hermes/config.yaml"
    log_level: str = "INFO"
    profiles: list[dict[str, Any]] = field(default_factory=list)
    terminal_shell: str | None = None

    # Media registry (inbound media for screenshot / attachment tools)
    media_max_size_mb: int = 100
    media_ttl_seconds: int = 86400
    media_lru_cap: int = 500
    # Extra allowed roots beyond the automatic tmp+workspace defaults.
    # MediaRegistry always appends these on top of its own defaults — they
    # do not replace the base list.
    media_allowed_roots: list[str] = field(default_factory=list)
    # Strict sandbox on /media/by-path. Default False: LLM-emitted
    # MEDIA:/abs/path markers are served as long as the file exists, is
    # a regular file, and fits under max_size. Set True (via
    # RELAY_MEDIA_STRICT_SANDBOX=1) to re-enable the allowed_roots check
    # on the phone-side direct-path route. The token path (loopback-only
    # /media/register) is ALWAYS strict regardless of this flag.
    media_strict_sandbox: bool = False

    @classmethod
    def from_env(cls) -> RelayConfig:
        """Build config from environment variables, falling back to defaults."""
        config = cls(
            host=os.getenv("RELAY_HOST", cls.host),
            port=int(os.getenv("RELAY_PORT", str(cls.port))),
            ssl_cert=os.getenv("RELAY_SSL_CERT"),
            ssl_key=os.getenv("RELAY_SSL_KEY"),
            webapi_url=os.getenv("RELAY_WEBAPI_URL", cls.webapi_url),
            hermes_config_path=os.getenv(
                "RELAY_HERMES_CONFIG", cls.hermes_config_path
            ),
            log_level=os.getenv("RELAY_LOG_LEVEL", cls.log_level),
            terminal_shell=os.getenv("RELAY_TERMINAL_SHELL") or None,
        )

        # ── Media knobs ─────────────────────────────────────────────────
        media_max_size = os.getenv("RELAY_MEDIA_MAX_SIZE_MB")
        if media_max_size:
            try:
                config.media_max_size_mb = int(media_max_size)
            except ValueError:
                logger.warning(
                    "Invalid RELAY_MEDIA_MAX_SIZE_MB=%r — using default %d",
                    media_max_size,
                    config.media_max_size_mb,
                )

        media_ttl = os.getenv("RELAY_MEDIA_TTL_SECONDS")
        if media_ttl:
            try:
                config.media_ttl_seconds = int(media_ttl)
            except ValueError:
                logger.warning(
                    "Invalid RELAY_MEDIA_TTL_SECONDS=%r — using default %d",
                    media_ttl,
                    config.media_ttl_seconds,
                )

        media_lru = os.getenv("RELAY_MEDIA_LRU_CAP")
        if media_lru:
            try:
                config.media_lru_cap = int(media_lru)
            except ValueError:
                logger.warning(
                    "Invalid RELAY_MEDIA_LRU_CAP=%r — using default %d",
                    media_lru,
                    config.media_lru_cap,
                )

        media_roots = os.getenv("RELAY_MEDIA_ALLOWED_ROOTS")
        if media_roots:
            config.media_allowed_roots = [
                r.strip() for r in media_roots.split(os.pathsep) if r.strip()
            ]

        strict = os.getenv("RELAY_MEDIA_STRICT_SANDBOX", "").strip().lower()
        if strict in ("1", "true", "yes", "on"):
            config.media_strict_sandbox = True

        config.profiles = _load_profiles(config.hermes_config_path)
        return config


def _load_profiles(config_path: str) -> list[dict[str, Any]]:
    """Load agent profiles from the Hermes config YAML.

    Returns a list of dicts with at least ``name`` and ``model`` keys.
    Returns an empty list if the file doesn't exist or can't be parsed.
    """
    resolved = Path(config_path).expanduser()
    if not resolved.is_file():
        logger.info("Hermes config not found at %s — no profiles loaded", resolved)
        return []

    try:
        with open(resolved, "r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
    except Exception:
        logger.warning("Failed to read Hermes config at %s", resolved, exc_info=True)
        return []

    if not isinstance(data, dict):
        return []

    raw_profiles = data.get("profiles") or data.get("agents") or []
    if not isinstance(raw_profiles, list):
        return []

    profiles: list[dict[str, Any]] = []
    for entry in raw_profiles:
        if isinstance(entry, dict) and "name" in entry:
            profiles.append(
                {
                    "name": entry["name"],
                    "model": entry.get("model", "unknown"),
                    "description": entry.get("description", ""),
                }
            )

    logger.info("Loaded %d agent profile(s) from %s", len(profiles), resolved)
    return profiles

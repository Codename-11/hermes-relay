"""Relay configuration — loaded from environment variables and config files."""

from __future__ import annotations

import json
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

    # Profile discovery — scan ``~/.hermes/profiles/*/`` for upstream-style
    # isolated profile directories. When False, ``_load_profiles`` returns an
    # empty list without touching the filesystem. Mirrors the existing
    # env-var-driven toggle pattern used for the media knobs above
    # (``RELAY_MEDIA_STRICT_SANDBOX`` etc.). Set
    # ``RELAY_PROFILE_DISCOVERY_ENABLED=0`` to disable.
    profile_discovery_enabled: bool = True

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

        # ── Profile discovery toggle ────────────────────────────────────
        discovery = os.getenv("RELAY_PROFILE_DISCOVERY_ENABLED", "").strip().lower()
        if discovery in ("0", "false", "no", "off"):
            config.profile_discovery_enabled = False
        elif discovery in ("1", "true", "yes", "on"):
            config.profile_discovery_enabled = True

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

        config.profiles = _load_profiles(
            config.hermes_config_path,
            enabled=config.profile_discovery_enabled,
        )
        return config


def _extract_description_from_soul(soul_text: str) -> str:
    """Return the first non-blank line of a SOUL.md, stripped of leading
    markdown heading markers and surrounding whitespace.

    Returns an empty string if the file contains no textual content.
    """
    for raw_line in soul_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        # Strip leading '#' characters (markdown headings) then whitespace.
        cleaned = line.lstrip("#").strip()
        if cleaned:
            return cleaned
    return ""


def _pid_is_alive(pid: int) -> bool:
    """Return True if ``pid`` refers to a live process on this host.

    Uses the POSIX ``os.kill(pid, 0)`` "probe" pattern — signal 0 performs
    the permission/existence check without delivering a real signal. On
    Windows, ``os.kill`` with signal 0 on CPython is implemented via
    ``OpenProcess`` and returns success for live PIDs, ``OSError`` with
    ``EINVAL``/``ESRCH``/``EPERM``-ish errno for dead or inaccessible
    ones. We treat any ``OSError`` as "not running" — we prefer
    false-negatives here (the gateway will simply be flagged offline) to
    false-positives that would claim a dead daemon is live.
    """
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
    except OSError:
        return False
    except Exception:  # pragma: no cover — defensive
        return False
    return True


def _probe_gateway_running(profile_home: Path) -> bool:
    """Check the per-profile ``gateway.pid`` file and probe liveness.

    The upstream Hermes CLI writes its daemon PID to ``<profile>/gateway.pid``
    on ``hermes platform start``. Reading that file + ``os.kill(pid, 0)``
    tells the phone whether the profile's API gateway is actually listening
    right now. Returns ``False`` on any filesystem or parse error — the
    feature is advisory, not load-bearing.
    """
    pid_file = profile_home / "gateway.pid"
    try:
        if not pid_file.is_file():
            return False
        raw = pid_file.read_text(encoding="utf-8").strip()
        if not raw:
            return False
        # Upstream Hermes writes JSON — {"pid": N, "kind": "...", ...}.
        # Older installs wrote a bare integer; tolerate both so the probe
        # keeps working across the upgrade boundary.
        try:
            parsed = json.loads(raw)
            pid = int(parsed["pid"]) if isinstance(parsed, dict) else int(parsed)
        except (json.JSONDecodeError, KeyError, TypeError):
            pid = int(raw.split()[0])
    except (OSError, ValueError, IndexError):
        return False
    except Exception:  # pragma: no cover — defensive
        return False
    return _pid_is_alive(pid)


def _count_profile_skills(profile_home: Path) -> int:
    """Count ``SKILL.md`` files under ``<profile>/skills/`` recursively.

    Returns 0 if the skills directory doesn't exist or is unreadable.
    """
    skills_dir = profile_home / "skills"
    if not skills_dir.is_dir():
        return 0
    try:
        return sum(1 for _ in skills_dir.rglob("SKILL.md"))
    except OSError:
        return 0
    except Exception:  # pragma: no cover — defensive
        return 0


def _read_profile_entry(
    name: str,
    config_yaml: Path,
    soul_md: Path,
    *,
    profile_home: Path,
) -> dict[str, Any] | None:
    """Read a single profile directory into the wire-shape dict.

    Returns ``None`` if ``config.yaml`` is missing or unreadable (caller
    logs a warning and skips). ``SOUL.md`` is optional. ``profile_home``
    is the directory used for the liveness/has-soul/skill-count probes —
    for directory-based profiles this is the profile directory itself;
    for the synthetic ``default`` profile this is ``~/.hermes``.
    """
    if not config_yaml.is_file():
        logger.warning(
            "Profile %r has no config.yaml at %s — skipping",
            name,
            config_yaml,
        )
        return None

    try:
        with open(config_yaml, "r", encoding="utf-8") as fh:
            data = yaml.safe_load(fh)
    except Exception:
        logger.warning(
            "Profile %r: failed to parse %s — skipping",
            name,
            config_yaml,
            exc_info=True,
        )
        return None

    if data is None:
        data = {}
    if not isinstance(data, dict):
        logger.warning(
            "Profile %r: %s did not parse to a mapping — skipping",
            name,
            config_yaml,
        )
        return None

    model_section = data.get("model")
    if isinstance(model_section, dict):
        model = model_section.get("default") or "unknown"
    else:
        model = "unknown"
    if not isinstance(model, str):
        model = str(model)

    soul_text: str | None = None
    if soul_md.is_file():
        try:
            soul_text = soul_md.read_text(encoding="utf-8").rstrip()
        except Exception:
            logger.warning(
                "Profile %r: failed to read %s — treating as absent",
                name,
                soul_md,
                exc_info=True,
            )
            soul_text = None

    description = data.get("description")
    if not isinstance(description, str) or not description.strip():
        if soul_text:
            description = _extract_description_from_soul(soul_text)
        else:
            description = ""
    else:
        description = description.strip()

    return {
        "name": name,
        "model": model,
        "description": description,
        "system_message": soul_text if soul_text else None,
        "gateway_running": _probe_gateway_running(profile_home),
        "has_soul": soul_md.is_file(),
        "skill_count": _count_profile_skills(profile_home),
    }


def _load_profiles(
    config_path: str,
    *,
    enabled: bool = True,
) -> list[dict[str, Any]]:
    """Discover agent profiles from the Hermes ``~/.hermes/`` layout.

    Upstream Hermes stores profiles as isolated directories under
    ``~/.hermes/profiles/<name>/``, each with its own ``config.yaml``,
    ``SOUL.md``, ``.env``, memory, and sessions. The root
    ``~/.hermes/config.yaml`` is surfaced as a synthetic ``"default"``
    profile so callers always see at least one entry when the host is
    configured at all.

    Each returned dict has the keys ``name``, ``model``, ``description``,
    and ``system_message`` (snake_case — this is the wire shape consumed
    by the Kotlin client via ``auth.ok``).

    When ``enabled=False`` returns an empty list without scanning — this
    honours the ``profile_discovery_enabled`` config toggle.
    """
    if not enabled:
        logger.info("Profile discovery disabled via config — returning empty list")
        return []

    root_config = Path(config_path).expanduser()
    hermes_dir = root_config.parent
    profiles_dir = hermes_dir / "profiles"

    results: list[dict[str, Any]] = []

    # Synthetic "default" entry mapped to the root config.
    if root_config.is_file():
        default_entry = _read_profile_entry(
            name="default",
            config_yaml=root_config,
            soul_md=hermes_dir / "SOUL.md",
            profile_home=hermes_dir,
        )
        if default_entry is not None:
            results.append(default_entry)
    else:
        logger.info(
            "Root Hermes config not found at %s — skipping default profile",
            root_config,
        )

    # Directory-based profiles.
    if profiles_dir.is_dir():
        # Sort for deterministic ordering across filesystems.
        for child in sorted(profiles_dir.iterdir()):
            if not child.is_dir():
                continue
            entry = _read_profile_entry(
                name=child.name,
                config_yaml=child / "config.yaml",
                soul_md=child / "SOUL.md",
                profile_home=child,
            )
            if entry is not None:
                results.append(entry)
    else:
        logger.debug(
            "No profiles directory at %s — only default profile surfaced",
            profiles_dir,
        )

    logger.info(
        "Discovered %d profile(s) under %s",
        len(results),
        hermes_dir,
    )
    return results
